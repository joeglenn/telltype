#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! The whole server started as the binary starts it (configuration file, credentials, TLS,
//! background model load, graceful shutdown), with fake upstreams and a fake recogniser.

use std::io::{Read as _, Write as _};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use telltype_config::Config;
use telltype_server::{Loader, ServerError, credentials, fingerprint, serve};
use telltype_speech::testing::{FakeRecognizer, wav_tone};
use telltype_speech::{LoadError, Recognizer};
use telltype_upstream::testing::FakeUpstream;
use tokio::sync::oneshot;

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telltype-serve-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn write_config(dir: &Path, tls: &str, cleanup: &str) -> Config {
    let text = format!(
        "[server]\nlisten = \"127.0.0.1:0\"\nshutdown_grace_s = 3\n\n{tls}\n\
         [auth]\nkey_file = \"gate.key\"\n\n[speech]\nmodel_dir = \"/nonexistent\"\nmodel_name = \"fake-parakeet\"\n\n\
         [text.cleanup]\nurl = \"{cleanup}\"\nmodel = \"cleanup-model\"\naliases = {{ cleanup = \"cleanup\" }}\n"
    );
    let path = dir.join("server.toml");
    std::fs::write(&path, text).unwrap();
    Config::load(&path).unwrap()
}

fn fake_loader(delay: Duration, recognizer: FakeRecognizer) -> Loader {
    Box::new(move || {
        std::thread::sleep(delay);
        Ok(Arc::new(recognizer) as Arc<dyn Recognizer>)
    })
}

struct Running {
    addr: SocketAddr,
    stop: oneshot::Sender<()>,
    task: tokio::task::JoinHandle<Result<(), ServerError>>,
}

async fn start(config: Config, loader: Loader) -> Running {
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
    Running { addr, stop, task }
}

fn key(dir: &Path) -> String {
    std::fs::read_to_string(dir.join("gate.key"))
        .unwrap()
        .trim()
        .to_owned()
}

fn multipart(wav: &[u8]) -> (String, Vec<u8>) {
    let boundary = "b0undary";
    let mut body =
        format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\n")
            .into_bytes();
    body.extend_from_slice(wav);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    (format!("multipart/form-data; boundary={boundary}"), body)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn plain_http_readiness_requests_and_graceful_shutdown() {
    let dir = scratch("http");
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let config = write_config(
        &dir,
        "[tls]\nmode = \"off\"\nallow_plain_http = true\n",
        &cleanup.url(),
    );
    let report = credentials(&config, false, false).unwrap();
    assert!(
        report.contains("key: created") && report.contains("certificate: none"),
        "{report}"
    );
    assert!(!report.contains(&key(&dir)), "the key is never printed");

    let recognizer = FakeRecognizer::new("hello from the fake").with_delay(Duration::from_millis(600));
    let running = start(config, fake_loader(Duration::from_millis(400), recognizer)).await;
    let base = format!("http://{}", running.addr);
    let client = reqwest::Client::new();
    let auth = format!("Bearer {}", key(&dir));

    let health = client
        .get(format!("{base}/healthz"))
        .header("authorization", &auth)
        .send()
        .await
        .unwrap();
    assert_eq!(health.status(), 503, "503 until the model is loaded");
    let mut ready = false;
    for _ in 0..50 {
        let response = client
            .get(format!("{base}/healthz"))
            .header("authorization", &auth)
            .send()
            .await
            .unwrap();
        if response.status() == 200 {
            let body: serde_json::Value = serde_json::from_slice(&response.bytes().await.unwrap()).unwrap();
            assert_eq!(body["status"], "ok");
            assert_eq!(body["stt"]["model"], "fake-parakeet");
            assert_eq!(
                body["assistant"],
                serde_json::json!({"ready": false, "model": ""}),
                "no commands upstream"
            );
            ready = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(ready);
    assert_eq!(
        client
            .get(format!("{base}/healthz"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );

    // A transcription that is still running when shutdown starts finishes within the grace period.
    let (content_type, body) = multipart(&wav_tone(0.5, 16_000, 1));
    let slow = tokio::spawn({
        let request = client
            .post(format!("{base}/v1/audio/transcriptions"))
            .header("authorization", &auth)
            .header("content-type", content_type)
            .body(body);
        async move {
            serde_json::from_slice::<serde_json::Value>(&request.send().await.unwrap().bytes().await.unwrap())
                .unwrap()
        }
    });
    tokio::time::sleep(Duration::from_millis(200)).await;
    running.stop.send(()).unwrap();
    let reply = slow.await.unwrap();
    assert_eq!(reply["text"], "hello from the fake");
    running.task.await.unwrap().unwrap();
    assert!(
        client.get(format!("{base}/healthz")).send().await.is_err(),
        "no longer listening"
    );
    std::fs::remove_dir_all(&dir).unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn https_with_the_generated_self_signed_certificate() {
    let dir = scratch("tls");
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let config = write_config(
        &dir,
        "[tls]\nmode = \"self-signed\"\nsubject_alt_names = [\"localhost\", \"127.0.0.1\"]\n",
        &cleanup.url(),
    );
    credentials(&config, false, false).unwrap();
    let prints = fingerprint(&config).unwrap();
    assert!(prints.contains("spki sha256 (base64): sha256/"), "{prints}");
    let running = start(config, fake_loader(Duration::ZERO, FakeRecognizer::new("x"))).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    let cert_pem = std::fs::read(dir.join("tls/cert.pem")).unwrap();
    let addr = running.addr;
    let auth = key(&dir);
    let reply = tokio::task::spawn_blocking(move || https_get(addr, Some(&cert_pem), "/healthz", &auth))
        .await
        .unwrap()
        .unwrap();
    assert!(reply.starts_with("HTTP/1.1 200"), "{reply}");
    assert!(reply.contains("\"status\":\"ok\""), "{reply}");
    let untrusted = tokio::task::spawn_blocking(move || https_get(addr, None, "/healthz", ""))
        .await
        .unwrap();
    assert!(
        untrusted.is_err(),
        "a client without the pin must refuse the certificate"
    );

    running.stop.send(()).unwrap();
    running.task.await.unwrap().unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
}

/// A minimal HTTPS/1.1 GET with rustls, trusting only `cert_pem` (or nothing).
fn https_get(addr: SocketAddr, cert_pem: Option<&[u8]>, path: &str, key: &str) -> Result<String, String> {
    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, ServerName};
    let mut roots = rustls::RootCertStore::empty();
    if let Some(pem) = cert_pem {
        for cert in CertificateDer::pem_slice_iter(pem) {
            roots.add(cert.unwrap()).unwrap();
        }
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let name = ServerName::try_from("127.0.0.1").unwrap();
    let mut connection = rustls::ClientConnection::new(Arc::new(config), name).unwrap();
    let mut socket = std::net::TcpStream::connect(addr).map_err(|e| e.to_string())?;
    socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut tls = rustls::Stream::new(&mut connection, &mut socket);
    let request = format!(
        "GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {key}\r\nConnection: close\r\n\r\n"
    );
    tls.write_all(request.as_bytes()).map_err(|e| e.to_string())?;
    let mut reply = String::new();
    match tls.read_to_string(&mut reply) {
        Ok(_) => Ok(reply),
        Err(e) if !reply.is_empty() && e.kind() == std::io::ErrorKind::UnexpectedEof => Ok(reply),
        Err(e) => Err(e.to_string()),
    }
}

#[tokio::test]
async fn a_failing_model_load_stops_the_server_with_an_error() {
    let dir = scratch("load");
    let config = write_config(
        &dir,
        "[tls]\nmode = \"off\"\nallow_plain_http = true\n",
        "http://127.0.0.1:9",
    );
    credentials(&config, false, false).unwrap();
    let loader: Loader = Box::new(|| Err(LoadError::MissingFile("/nonexistent/encoder.int8.onnx".into())));
    let running = start(config, loader).await;
    let result = tokio::time::timeout(Duration::from_secs(5), running.task)
        .await
        .unwrap()
        .unwrap();
    let error = result.unwrap_err();
    assert!(matches!(error, ServerError::ModelLoad(_)), "{error}");
    assert_eq!(error.exit_code(), 1);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[tokio::test]
async fn start_up_errors_are_typed_with_exit_code_2() {
    let dir = scratch("errors");
    let config = write_config(&dir, "[tls]\nmode = \"files\"\n", "http://127.0.0.1:9");
    // No key file yet.
    let (tx, _rx) = oneshot::channel();
    let error = serve(
        config.clone(),
        fake_loader(Duration::ZERO, FakeRecognizer::new("x")),
        async {},
        Some(tx),
    )
    .await
    .unwrap_err();
    assert!(matches!(error, ServerError::Credentials(_)), "{error}");
    assert_eq!(error.exit_code(), 2);
    // Key present, certificate files missing in "files" mode.
    assert!(matches!(
        credentials(&config, false, false),
        Err(ServerError::Credentials(_))
    ));
    let (tx, _rx) = oneshot::channel();
    let error = serve(
        config,
        fake_loader(Duration::ZERO, FakeRecognizer::new("x")),
        async {},
        Some(tx),
    )
    .await
    .unwrap_err();
    assert!(error.to_string().contains("cert.pem"), "{error}");
    std::fs::remove_dir_all(&dir).unwrap();
}
