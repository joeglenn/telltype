#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]
//! Shared helpers of the API tests: a server state with fake upstreams and a fake recogniser.
#![allow(dead_code, reason = "each test binary uses a different subset")]

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum::body::Body;
use axum::http::{Request, Response, StatusCode};
use bytes::Bytes;
use http_body_util::BodyExt;
use serde_json::{Value, json};
use telltype_http::{AppState, ClientPolicy, Guard, HttpLimits, KeyStore, router};
use telltype_speech::testing::FakeRecognizer;
use telltype_speech::{EngineLimits, SpeechEngine};
use telltype_upstream::testing::FakeUpstream;
use telltype_upstream::{UpstreamSpec, Upstreams};
use tower::ServiceExt;

pub const KEY: &str = "test-key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
pub const SECRET_TRANSCRIPT: &str = "the secret words nobody may log";
pub const BOUNDARY: &str = "telltype-test-boundary";

pub struct Harness {
    pub app: Router,
    pub cleanup: FakeUpstream,
    pub commands: FakeUpstream,
    pub recognizer: Arc<FakeRecognizer>,
    pub engine: Arc<SpeechEngine>,
}

pub struct Options {
    pub ready: bool,
    pub recognizer: FakeRecognizer,
    pub limits: HttpLimits,
    pub engine: EngineLimits,
    pub upstream_in_flight: usize,
    pub key: bool,
    /// Replaces the one `KEY` (named `tester`).
    pub keys: Option<Arc<KeyStore>>,
    pub policy: ClientPolicy,
    /// The commands upstream's allow-list (cleanup allows its model and alias only).
    pub allow: Vec<String>,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            ready: true,
            recognizer: FakeRecognizer::new(SECRET_TRANSCRIPT),
            limits: HttpLimits {
                max_upload_bytes: 25 << 20,
                multipart_overhead_bytes: 1 << 20,
                max_json_bytes: 1 << 20,
                retry_after_s: 5,
                body_grace: Duration::from_secs(10),
                body_min_bytes_per_s: 4096,
            },
            engine: EngineLimits {
                max_concurrent: 2,
                max_waiting: 4,
                max_audio_seconds: 360.0,
                recognition_timeout: Duration::from_secs(5),
                max_hotwords: None,
            },
            upstream_in_flight: 4,
            key: true,
            keys: None,
            policy: ClientPolicy::default(),
            allow: vec!["*".to_owned()],
        }
    }
}

pub fn spec(role: &str, url: String, alias: (&str, &str), in_flight: usize) -> UpstreamSpec {
    UpstreamSpec {
        role: role.to_owned(),
        url,
        model: format!("model-{role}"),
        aliases: BTreeMap::from([(alias.0.to_owned(), alias.1.to_owned())]),
        timeout: Duration::from_millis(800),
        max_in_flight: in_flight,
        health_path: "/health".to_owned(),
        allow: Vec::new(),
    }
}

pub async fn harness(options: Options) -> Harness {
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let commands = FakeUpstream::start("assistant", &["qwen3.5-4b-q4_k_m", "gemma-4-12b"])
        .await
        .unwrap();
    let upstreams = Upstreams::new(
        vec![
            spec(
                "cleanup",
                cleanup.url(),
                ("cleanup", "cleanup"),
                options.upstream_in_flight,
            ),
            UpstreamSpec {
                allow: options.allow.clone(),
                ..spec(
                    "commands",
                    commands.url(),
                    ("assistant", "qwen3.5-4b-q4_k_m"),
                    options.upstream_in_flight,
                )
            },
        ],
        Duration::from_millis(300),
    )
    .unwrap();
    let engine = Arc::new(SpeechEngine::new(options.engine));
    let recognizer = Arc::new(options.recognizer);
    if options.ready {
        engine.set_recognizer(recognizer.clone());
    }
    let state = AppState {
        keys: options.keys.clone().or_else(|| {
            options
                .key
                .then(|| Arc::new(KeyStore::fixed(&[("tester", KEY.as_bytes(), None)])))
        }),
        guard: Arc::new(Guard::new(options.policy.clone())),
        speech: engine.clone(),
        speech_model: "model-stt".to_owned(),
        upstreams: Arc::new(upstreams),
        limits: options.limits,
    };
    Harness {
        app: router(state),
        cleanup,
        commands,
        recognizer,
        engine,
    }
}

pub fn form(file: Option<&[u8]>, fields: &[(&str, &str)]) -> Vec<u8> {
    let mut body = Vec::new();
    if let Some(file) = file {
        body.extend_from_slice(
            format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .as_bytes(),
        );
        body.extend_from_slice(file);
        body.extend_from_slice(b"\r\n");
    }
    for (name, value) in fields {
        body.extend_from_slice(
            format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n")
                .as_bytes(),
        );
    }
    body.extend_from_slice(format!("--{BOUNDARY}--\r\n").as_bytes());
    body
}

pub fn upload(body: Vec<u8>) -> Request<Body> {
    Request::post("/v1/audio/transcriptions")
        .header("authorization", format!("Bearer {KEY}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from(body))
        .unwrap()
}

pub fn chat_request(body: impl Into<Body>) -> Request<Body> {
    Request::post("/v1/chat/completions")
        .header("authorization", format!("Bearer {KEY}"))
        .header("content-type", "application/json")
        .body(body.into())
        .unwrap()
}

pub fn chat_json(model: &str) -> String {
    json!({"model": model, "messages": [{"role": "user", "content": "hello"}], "temperature": 0.1})
        .to_string()
}

pub fn get(path: &str) -> Request<Body> {
    Request::get(path)
        .header("authorization", format!("Bearer {KEY}"))
        .body(Body::empty())
        .unwrap()
}

pub async fn send(app: &Router, request: Request<Body>) -> (StatusCode, axum::http::HeaderMap, Bytes) {
    let response: Response<Body> = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, headers, body)
}

pub fn json_of(body: &Bytes) -> Value {
    serde_json::from_slice(body).unwrap_or_else(|_| panic!("not JSON: {}", String::from_utf8_lossy(body)))
}

pub fn code(body: &Bytes) -> String {
    json_of(body)["error"]["code"]
        .as_str()
        .unwrap_or_default()
        .to_owned()
}
