#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! The running server against hostile clients over real TCP: slowloris, idle keep-alive, trickled
//! bodies, oversized uploads, and named keys added and revoked while it runs.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use telltype_config::Config;
use telltype_http::keys::{add_key, read_key_file, revoke_key};
use telltype_server::{Loader, ServerError, serve};
use telltype_speech::Recognizer;
use telltype_speech::testing::FakeRecognizer;
use telltype_upstream::testing::FakeUpstream;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::oneshot;

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telltype-hardening-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

struct Running {
    addr: SocketAddr,
    dir: PathBuf,
    key: String,
    _cleanup: FakeUpstream,
    stop: oneshot::Sender<()>,
    task: tokio::task::JoinHandle<Result<(), ServerError>>,
}

impl Running {
    async fn stop(self) {
        let _ = self.stop.send(());
        self.task.await.unwrap().unwrap();
        std::fs::remove_dir_all(&self.dir).unwrap();
    }
}

async fn start(name: &str) -> Running {
    let dir = scratch(name);
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let keys = dir.join("keys");
    let alice = add_key(&keys, "alice", None).unwrap();
    let key = String::from_utf8(read_key_file(&alice).unwrap().0).unwrap();
    let text = format!(
        "[server]\nlisten = \"127.0.0.1:0\"\nshutdown_grace_s = 1\n\n\
         [limits]\nheader_timeout_s = 1\nidle_timeout_s = 3\nexempt = []\n\
         max_connections_per_ip = 8\nbody_grace_s = 0.5\nbody_min_bytes_per_s = 1000\nauth_failures_per_minute = 100\n\n\
         [tls]\nmode = \"off\"\nallow_plain_http = true\n\n[auth]\nkeys_dir = \"keys\"\n\n\
         [speech]\nmodel_dir = \"/nonexistent\"\nmodel_name = \"fake\"\nrecognition_timeout_s = 2\n\n\
         [text.cleanup]\nurl = \"{}\"\nmodel = \"cleanup-model\"\naliases = {{ cleanup = \"cleanup\" }}\ntimeout_s = 2\n",
        cleanup.url()
    );
    std::fs::write(dir.join("server.toml"), text).unwrap();
    let config = Config::load(&dir.join("server.toml")).unwrap();
    let loader: Loader = Box::new(|| Ok(Arc::new(FakeRecognizer::new("hello")) as Arc<dyn Recognizer>));
    let (stop, stopped) = oneshot::channel::<()>();
    let (tx, rx) = oneshot::channel();
    let task = tokio::spawn(serve(
        config,
        loader,
        async {
            let _ = stopped.await;
        },
        Some(tx),
    ));
    let addr = rx.await.unwrap();
    Running {
        addr,
        dir,
        key,
        _cleanup: cleanup,
        stop,
        task,
    }
}

async fn connect_from(source: &str, addr: SocketAddr) -> std::io::Result<TcpStream> {
    let socket = TcpSocket::new_v4()?;
    socket.bind(format!("{source}:0").parse().unwrap())?;
    socket.connect(addr).await
}

/// Read until the server closes the connection (or `limit`); the bytes and when it closed.
async fn read_until_closed(stream: &mut TcpStream, limit: Duration) -> (Vec<u8>, Option<Duration>) {
    let started = Instant::now();
    let mut data = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        match tokio::time::timeout_at((started + limit).into(), stream.read(&mut buf)).await {
            Err(_) => return (data, None),
            Ok(Ok(0) | Err(_)) => return (data, Some(started.elapsed())),
            Ok(Ok(n)) => data.extend_from_slice(&buf[..n]),
        }
    }
}

async fn healthz(addr: SocketAddr, key: &str) -> u16 {
    reqwest::Client::new()
        .get(format!("http://{addr}/healthz"))
        .bearer_auth(key)
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .map_or(0, |r| r.status().as_u16())
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn slowloris_cannot_block_a_normal_request() {
    let server = start("slowloris").await;
    let addr = server.addr;
    // 40 connections from another address, each sending its headers one byte every 100 ms.
    let mut attackers = Vec::new();
    for _ in 0..40 {
        attackers.push(tokio::spawn(async move {
            let Ok(mut stream) = connect_from("127.0.0.2", addr).await else {
                return (true, Duration::ZERO);
            };
            let started = Instant::now();
            for byte in
                b"GET /healthz HTTP/1.1\r\nHost: x\r\nX-Slow: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    .iter()
                    .cycle()
                    .take(200)
            {
                if stream.write_all(&[*byte]).await.is_err() {
                    return (true, started.elapsed());
                }
                let mut probe = [0u8; 1];
                if let Ok(Ok(0) | Err(_)) =
                    tokio::time::timeout(Duration::from_millis(100), stream.read(&mut probe)).await
                {
                    return (true, started.elapsed());
                }
            }
            (false, started.elapsed())
        }));
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    // Meanwhile a normal client gets its answers at once, again and again.
    for _ in 0..5 {
        let started = Instant::now();
        assert_eq!(healthz(addr, &server.key).await, 200);
        assert!(
            started.elapsed() < Duration::from_millis(800),
            "{:?}",
            started.elapsed()
        );
    }
    let mut closed_early = 0;
    let mut slowest = Duration::ZERO;
    for attacker in attackers {
        let (closed, after) = attacker.await.unwrap();
        assert!(closed, "a slow client kept its connection for 20 s");
        if after < Duration::from_millis(500) {
            closed_early += 1;
        }
        slowest = slowest.max(after);
    }
    eprintln!(
        "slowloris: 40 slow connections, {closed_early} refused at once (per-address cap 8), the rest closed by the 1 s header timeout; longest held {slowest:?}"
    );
    assert!(closed_early >= 32, "{closed_early}");
    assert!(slowest < Duration::from_secs(3), "{slowest:?}");
    server.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn idle_keep_alive_and_trickled_bodies_are_closed() {
    let server = start("idle").await;
    let addr = server.addr;
    // An idle keep-alive connection is closed after the header timeout.
    let mut idle = TcpStream::connect(addr).await.unwrap();
    let request = format!(
        "GET /healthz HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {}\r\n\r\n",
        server.key
    );
    idle.write_all(request.as_bytes()).await.unwrap();
    let (data, closed) = read_until_closed(&mut idle, Duration::from_secs(5)).await;
    assert!(String::from_utf8_lossy(&data).starts_with("HTTP/1.1 200"));
    let closed = closed.expect("an idle connection stayed open");
    assert!(closed < Duration::from_secs(3), "{closed:?}");

    // A body trickled at 1 byte per 200 ms is cut off with 408 and the connection closed.
    let mut slow = TcpStream::connect(addr).await.unwrap();
    let head = format!(
        "POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n",
        server.key
    );
    slow.write_all(head.as_bytes()).await.unwrap();
    let started = Instant::now();
    let writer = tokio::spawn(async move {
        for _ in 0..50 {
            if slow.write_all(b"{").await.is_err() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
            let mut buf = [0u8; 4096];
            if let Ok(Ok(n)) = tokio::time::timeout(Duration::from_millis(1), slow.read(&mut buf)).await {
                return Some((String::from_utf8_lossy(&buf[..n]).into_owned(), started.elapsed()));
            }
        }
        None
    });
    let (reply, after) = writer
        .await
        .unwrap()
        .expect("the trickled body was never cut off");
    eprintln!(
        "trickled body: cut off after {after:?}: {}",
        reply.lines().next().unwrap_or("")
    );
    assert!(reply.starts_with("HTTP/1.1 408"), "{reply}");
    assert!(reply.to_ascii_lowercase().contains("connection: close"));
    assert!(after < Duration::from_secs(3), "{after:?}");
    server.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn oversized_uploads_get_413_without_being_read() {
    let server = start("oversized").await;
    for expect in [false, true] {
        let mut stream = TcpStream::connect(server.addr).await.unwrap();
        let head = format!(
            "POST /v1/audio/transcriptions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {}\r\n\
             Content-Type: multipart/form-data; boundary=b\r\nContent-Length: 104857600\r\n{}\r\n",
            server.key,
            if expect { "Expect: 100-continue\r\n" } else { "" }
        );
        stream.write_all(head.as_bytes()).await.unwrap();
        if !expect {
            stream.write_all(&[b'x'; 16 * 1024]).await.unwrap();
        }
        let (data, closed) = read_until_closed(&mut stream, Duration::from_secs(3)).await;
        let text = String::from_utf8_lossy(&data);
        assert!(text.starts_with("HTTP/1.1 413"), "expect={expect}: {text}");
        assert!(closed.is_some(), "the connection was kept open for the body");
    }
    server.stop().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn named_keys_are_added_and_revoked_without_a_restart() {
    let server = start("keys").await;
    let (addr, keys) = (server.addr, server.dir.join("keys"));
    assert_eq!(healthz(addr, &server.key).await, 200);
    let bob = add_key(&keys, "bob", Some(&["speech".to_owned()])).unwrap();
    let bob = String::from_utf8(read_key_file(&bob).unwrap().0).unwrap();
    assert!(wait_for(addr, &bob, 200).await, "a new key was not picked up");
    revoke_key(&keys, "alice").unwrap();
    assert!(
        wait_for(addr, &server.key, 401).await,
        "a revoked key kept working"
    );
    assert_eq!(healthz(addr, &bob).await, 200);
    server.stop().await;
}

async fn wait_for(addr: SocketAddr, key: &str, status: u16) -> bool {
    let deadline = Instant::now() + Duration::from_secs(6);
    while Instant::now() < deadline {
        if healthz(addr, key).await == status {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    false
}

#[allow(dead_code, reason = "keeps the helper's signature honest")]
fn _unused(_: &Path) {}
