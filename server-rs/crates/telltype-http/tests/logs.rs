#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! The request log: one JSON line per request, metadata only. A test binary of its own, because a
//! thread-local tracing subscriber races with other tests' callsite registration.

mod common;

use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::Request;
use common::*;
use serde_json::{Value, json};
use telltype_speech::testing::wav_tone;

#[derive(Clone, Default)]
struct Captured(Arc<Mutex<Vec<u8>>>);

impl std::io::Write for Captured {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[tokio::test]
async fn one_log_line_per_request_without_secrets() {
    let captured = Captured::default();
    let writer = captured.clone();
    let subscriber = tracing_subscriber::fmt()
        .json()
        .flatten_event(true)
        .with_max_level(tracing::Level::DEBUG)
        .with_writer(move || writer.clone())
        .finish();
    let _guard = tracing::subscriber::set_default(subscriber);

    let h = harness(Options::default()).await;
    let mut request = upload(form(
        Some(&wav_tone(0.2, 16_000, 1)),
        &[("prompt", "PrivateTerm")],
    ));
    request
        .headers_mut()
        .insert("x-request-id", "log-1".parse().unwrap());
    send(&h.app, request).await;
    let mut request = chat_request(
        json!({"model": "cleanup", "messages": [{"role": "user", "content": SECRET_TRANSCRIPT}]}).to_string(),
    );
    request
        .headers_mut()
        .insert("x-request-id", "log-2".parse().unwrap());
    send(&h.app, request).await;
    let request = Request::get("/healthz")
        .header("authorization", "Bearer nope")
        .header("x-request-id", "log-3")
        .body(Body::empty())
        .unwrap();
    send(&h.app, request).await;

    let text = String::from_utf8(captured.0.lock().unwrap().clone()).unwrap();
    let entries: Vec<Value> = text
        .lines()
        .map(|l| serde_json::from_str::<Value>(l).unwrap())
        .filter(|e| e["target"] == "telltype::request")
        .collect();
    assert_eq!(entries.len(), 3, "{text}");
    let field = |i: usize, k: &str| entries[i][k].clone();
    assert_eq!(
        [
            field(0, "request_id"),
            field(1, "request_id"),
            field(2, "request_id")
        ],
        [json!("log-1"), json!("log-2"), json!("log-3")]
    );
    assert_eq!(
        [field(0, "status"), field(1, "status"), field(2, "status")],
        [json!(200), json!(200), json!(401)]
    );
    assert_eq!(
        (field(0, "backend"), field(1, "backend"), field(2, "code")),
        (json!("speech"), json!("cleanup"), json!("unauthorized"))
    );
    // The key's name, never the key.
    assert_eq!(
        (field(0, "key"), field(1, "key"), field(2, "key")),
        (json!("tester"), json!("tester"), json!(""))
    );
    assert!(entries.iter().all(|e| e["bytes_out"].as_u64().unwrap() > 0));
    assert!(field(0, "bytes_in").as_u64().unwrap() > 0);
    assert!(field(0, "timing").as_str().unwrap().starts_with("decode;dur="));
    for secret in [KEY, SECRET_TRANSCRIPT, "PrivateTerm", "nope", "Six pm"] {
        assert!(!text.contains(secret), "{secret} leaked into the log");
    }
}
