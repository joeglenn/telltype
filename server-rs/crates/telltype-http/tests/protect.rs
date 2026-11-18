#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! Public-facing protection: per-client rate limits, early rejection without reading the body,
//! slow bodies, the model allow-list and keys limited to roles.

mod common;

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::Request;
use bytes::Bytes;
use common::*;
use telltype_http::{ClientPolicy, KeyStore};
use telltype_speech::testing::wav_tone;

fn from(mut request: Request<Body>, peer: &str) -> Request<Body> {
    let addr: SocketAddr = format!("{peer}:40000").parse().unwrap();
    request.extensions_mut().insert(ConnectInfo(addr));
    request
}

fn with_key(mut request: Request<Body>, key: &str) -> Request<Body> {
    request
        .headers_mut()
        .insert("authorization", format!("Bearer {key}").parse().unwrap());
    request
}

fn policy(requests: u32, failures: u32, proxies: &[&str]) -> ClientPolicy {
    ClientPolicy {
        requests_per_minute: requests,
        auth_failures_per_minute: failures,
        max_clients: 1000,
        trusted_proxies: proxies.iter().map(|p| p.parse().unwrap()).collect(),
        exempt: Vec::new(),
    }
}

/// The policy a configuration without any `[limits]` key gets.
fn default_policy() -> ClientPolicy {
    let text = "[auth]\nkey_file = \"k\"\n[speech]\nmodel_dir = \"/m\"\n[text.cleanup]\nmodel = \"c\"\n";
    let config = telltype_config::Config::parse(text, std::path::Path::new("/"), None).unwrap();
    let rate = config.server.rate;
    ClientPolicy {
        requests_per_minute: rate.requests_per_minute,
        auth_failures_per_minute: rate.auth_failures_per_minute,
        max_clients: rate.max_clients,
        trusted_proxies: rate.trusted_proxies,
        exempt: rate.exempt,
    }
}

#[tokio::test]
async fn tailscale_lan_and_docker_clients_pass_with_the_defaults() {
    let h = harness(Options {
        policy: default_policy(),
        ..Options::default()
    })
    .await;
    // Well past the default 600 requests and 10 failed keys per minute.
    for peer in [
        "100.101.102.103",
        "192.168.1.20",
        "172.17.0.2",
        "fd7a:115c:a1e0::1",
    ] {
        let peer_addr = |p: &str| {
            if p.contains(':') {
                format!("[{p}]")
            } else {
                p.to_owned()
            }
        };
        for _ in 0..20 {
            assert_eq!(
                send(&h.app, from(with_key(get("/healthz"), "wrong"), &peer_addr(peer)))
                    .await
                    .0
                    .as_u16(),
                401
            );
        }
        for _ in 0..650 {
            let (status, _, _) = send(&h.app, from(get("/v1/models"), &peer_addr(peer))).await;
            assert_eq!(status.as_u16(), 200, "{peer}");
        }
        // The app's request shapes: a transcription upload and a chat request with the key only.
        let (status, _, _) = send(
            &h.app,
            from(
                upload(form(Some(&wav_tone(0.3, 16_000, 1)), &[("model", "whisper-1")])),
                &peer_addr(peer),
            ),
        )
        .await;
        assert_eq!(status.as_u16(), 200, "{peer}");
        let (status, _, _) = send(&h.app, from(chat_request(chat_json("cleanup")), &peer_addr(peer))).await;
        assert_eq!(status.as_u16(), 200, "{peer}");
    }
    // A public address is still limited by the same defaults.
    for _ in 0..10 {
        send(&h.app, from(with_key(get("/healthz"), "wrong"), "203.0.113.50")).await;
    }
    assert_eq!(
        send(&h.app, from(get("/healthz"), "203.0.113.50"))
            .await
            .0
            .as_u16(),
        429
    );
}

#[tokio::test]
async fn failed_keys_then_429_with_retry_after_and_no_key_check() {
    let h = harness(Options {
        policy: policy(600, 3, &[]),
        ..Options::default()
    })
    .await;
    for _ in 0..3 {
        let (status, headers, body) =
            send(&h.app, from(with_key(get("/healthz"), "wrong"), "192.0.2.1")).await;
        assert_eq!((status.as_u16(), code(&body).as_str()), (401, "unauthorized"));
        assert_eq!(headers["connection"], "close");
    }
    // Blocked: even the right key gets 429, without being checked.
    let (status, headers, body) = send(&h.app, from(get("/healthz"), "192.0.2.1")).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (429, "rate_limited"));
    let retry: u64 = headers["retry-after"].to_str().unwrap().parse().unwrap();
    assert!((1..=20).contains(&retry), "{retry}");
    assert_eq!(headers["connection"], "close");
    // Other clients are not affected.
    let (status, _, _) = send(&h.app, from(get("/healthz"), "192.0.2.2")).await;
    assert_eq!(status.as_u16(), 200);
}

#[tokio::test]
async fn overall_request_rate_per_client() {
    let h = harness(Options {
        policy: policy(2, 10, &[]),
        ..Options::default()
    })
    .await;
    for _ in 0..2 {
        assert_eq!(
            send(&h.app, from(get("/v1/models"), "198.51.100.1"))
                .await
                .0
                .as_u16(),
            200
        );
    }
    let (status, headers, body) = send(&h.app, from(get("/v1/models"), "198.51.100.1")).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (429, "rate_limited"));
    assert!(headers.contains_key("retry-after"));
    assert_eq!(
        send(&h.app, from(get("/v1/models"), "198.51.100.2"))
            .await
            .0
            .as_u16(),
        200
    );
}

#[tokio::test]
async fn forwarded_for_counts_only_behind_a_trusted_proxy() {
    let h = harness(Options {
        policy: policy(600, 1, &["127.0.0.1"]),
        ..Options::default()
    })
    .await;
    let via = |xff: &str, peer: &str, key: &str| {
        let mut request = from(with_key(get("/healthz"), key), peer);
        request
            .headers_mut()
            .insert("x-forwarded-for", xff.parse().unwrap());
        request
    };
    assert_eq!(
        send(&h.app, via("203.0.113.1", "127.0.0.1", "bad"))
            .await
            .0
            .as_u16(),
        401
    );
    assert_eq!(
        send(&h.app, via("203.0.113.1", "127.0.0.1", KEY))
            .await
            .0
            .as_u16(),
        429
    );
    assert_eq!(
        send(&h.app, via("203.0.113.2", "127.0.0.1", KEY))
            .await
            .0
            .as_u16(),
        200,
        "another client behind the proxy"
    );
    // An untrusted peer cannot pick its bucket with the header.
    assert_eq!(
        send(&h.app, via("203.0.113.9", "192.0.2.7", "bad"))
            .await
            .0
            .as_u16(),
        401
    );
    assert_eq!(
        send(&h.app, via("203.0.113.8", "192.0.2.7", KEY))
            .await
            .0
            .as_u16(),
        429
    );
    assert_eq!(
        send(&h.app, via("203.0.113.9", "127.0.0.1", KEY))
            .await
            .0
            .as_u16(),
        200
    );
}

/// A body that records whether anyone read it.
fn watched_body(read: &Arc<AtomicBool>, size: usize) -> Body {
    let read = Arc::clone(read);
    let chunk = Bytes::from(vec![b'x'; size]);
    Body::from_stream(futures_util::stream::once(async move {
        read.store(true, Ordering::SeqCst);
        Ok::<_, std::io::Error>(chunk)
    }))
}

#[tokio::test]
async fn oversized_or_unauthorised_requests_are_rejected_before_the_body_is_read() {
    let h = harness(Options::default()).await;
    let read = Arc::new(AtomicBool::new(false));
    let declared = 64 * 1024 * 1024;
    let request = Request::post("/v1/audio/transcriptions")
        .header("authorization", format!("Bearer {KEY}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .header("content-length", declared.to_string())
        .body(watched_body(&read, 10))
        .unwrap();
    let (status, headers, body) = send(&h.app, request).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    assert_eq!(headers["connection"], "close");
    let request = Request::post("/v1/chat/completions")
        .header("content-type", "application/json")
        .header("content-length", "1000")
        .body(watched_body(&read, 1000))
        .unwrap();
    let (status, headers, _) = send(&h.app, request).await;
    assert_eq!(
        (status.as_u16(), &headers["connection"]),
        (401, &"close".parse::<axum::http::HeaderValue>().unwrap())
    );
    assert!(!read.load(Ordering::SeqCst), "a rejected body was read");

    // Without a Content-Length the cap ends the read at once (no draining) and closes too.
    let request = chat_request(watched_body(&read, (1 << 20) + 1));
    let (status, headers, _) = send(&h.app, request).await;
    assert_eq!(status.as_u16(), 413);
    assert_eq!(headers["connection"], "close");
}

#[tokio::test]
async fn trickled_bodies_are_cut_off() {
    let mut options = Options::default();
    options.limits.body_grace = Duration::from_millis(300);
    options.limits.body_min_bytes_per_s = 1000;
    let h = harness(options).await;
    let trickle = futures_util::stream::unfold(0u32, |n| async move {
        (n < 100).then_some(())?;
        tokio::time::sleep(Duration::from_millis(100)).await;
        Some((Ok::<_, std::io::Error>(Bytes::from_static(b"{")), n + 1))
    });
    let started = Instant::now();
    let (status, headers, body) = send(&h.app, chat_request(Body::from_stream(trickle))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (408, "request_timeout"));
    assert_eq!(headers["connection"], "close");
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "{:?}",
        started.elapsed()
    );
    assert!(h.cleanup.seen().is_empty() && h.commands.seen().is_empty());

    // A normal upload at full speed is unaffected.
    let (status, _, _) = send(&h.app, upload(form(Some(&wav_tone(0.5, 16_000, 1)), &[]))).await;
    assert_eq!(status.as_u16(), 200);
}

#[tokio::test]
async fn only_allowed_models_are_listed_and_others_never_reach_an_upstream() {
    let h = harness(Options {
        allow: vec!["qwen*".to_owned()],
        ..Options::default()
    })
    .await;
    let models = json_of(&send(&h.app, get("/v1/models")).await.2);
    let ids: Vec<&str> = models["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["id"].as_str().unwrap())
        .collect();
    assert_eq!(
        ids,
        ["model-stt", "model-cleanup", "qwen3.5-4b-q4_k_m"],
        "{ids:?}"
    );
    let health = json_of(&send(&h.app, get("/healthz")).await.2);
    assert!(!health["models"].to_string().contains("gemma"), "{health}");
    for model in ["gemma-4-12b", "gpt-4o", ""] {
        let (status, _, body) = send(&h.app, chat_request(chat_json(model))).await;
        let expected = if model.is_empty() {
            (400, "bad_request")
        } else {
            (404, "model_not_found")
        };
        assert_eq!((status.as_u16(), code(&body).as_str()), expected, "{model}");
    }
    assert!(
        h.commands.seen().is_empty(),
        "the router saw a model outside the list"
    );
    let (status, _, _) = send(&h.app, chat_request(chat_json("qwen3.5-4b-q4_k_m"))).await;
    assert_eq!(status.as_u16(), 200);
}

#[tokio::test]
async fn keys_limited_to_roles() {
    let speech_key = "speech-only-key-xxxxxxxxxxxxxxxxxxxxxxxxxx";
    let text_key = "cleanup-only-key-xxxxxxxxxxxxxxxxxxxxxxxxx";
    let keys = KeyStore::fixed(&[
        ("phone", speech_key.as_bytes(), Some(&["speech"])),
        ("editor", text_key.as_bytes(), Some(&["cleanup"])),
    ]);
    let h = harness(Options {
        keys: Some(Arc::new(keys)),
        ..Options::default()
    })
    .await;
    let (status, _, body) = send(&h.app, with_key(chat_request(chat_json("cleanup")), speech_key)).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (404, "model_not_found"));
    let ok = send(
        &h.app,
        with_key(upload(form(Some(&wav_tone(0.3, 16_000, 1)), &[])), speech_key),
    )
    .await;
    assert_eq!(ok.0.as_u16(), 200);
    let models = json_of(&send(&h.app, with_key(get("/v1/models"), speech_key)).await.2);
    assert_eq!(models["data"].as_array().unwrap().len(), 1, "{models}");

    let (status, _, body) = send(
        &h.app,
        with_key(upload(form(Some(&wav_tone(0.3, 16_000, 1)), &[])), text_key),
    )
    .await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (403, "forbidden"));
    let (status, _, _) = send(&h.app, with_key(chat_request(chat_json("cleanup")), text_key)).await;
    assert_eq!(status.as_u16(), 200);
    let (status, _, body) = send(&h.app, with_key(chat_request(chat_json("assistant")), text_key)).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (404, "model_not_found"));
    assert!(h.commands.seen().is_empty());
    assert_eq!(
        send(&h.app, get("/healthz")).await.0.as_u16(),
        401,
        "the old key is not configured here"
    );
}
