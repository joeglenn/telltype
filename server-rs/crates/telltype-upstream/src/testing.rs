//! A fake `llama-server` for tests (feature `test-support`): `/health`, `/v1/models` and
//! `/v1/chat/completions` with controllable replies, recording what it was sent.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::Router;
use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use bytes::Bytes;

/// What the fake answers to a chat request.
#[derive(Clone, Debug)]
pub enum ChatBehaviour {
    /// 200 with `{"backend": <name>, "choices": [...]}`.
    Ok,
    /// This status and JSON body.
    Status(u16, String),
    /// Server-sent events: these chunks, `gap` apart.
    Stream(Vec<String>, Duration),
}

#[derive(Debug)]
pub struct FakeState {
    pub name: String,
    pub healthy: bool,
    pub health_delay: Duration,
    pub chat_delay: Duration,
    pub models: Vec<String>,
    pub models_status: u16,
    pub chat: ChatBehaviour,
    pub seen: Vec<(HeaderMap, Bytes)>,
}

/// A running fake upstream. Its state can be changed while it runs.
#[derive(Clone)]
pub struct FakeUpstream {
    pub addr: SocketAddr,
    pub state: Arc<Mutex<FakeState>>,
}

impl FakeUpstream {
    /// Start on a free localhost port, listing `models`.
    pub async fn start(name: &str, models: &[&str]) -> std::io::Result<Self> {
        let state = Arc::new(Mutex::new(FakeState {
            name: name.to_owned(),
            healthy: true,
            health_delay: Duration::ZERO,
            chat_delay: Duration::ZERO,
            models: models.iter().map(|m| (*m).to_owned()).collect(),
            models_status: 200,
            chat: ChatBehaviour::Ok,
            seen: Vec::new(),
        }));
        let app = Router::new()
            .route("/health", get(health))
            .route("/v1/models", get(models_route))
            .route("/v1/chat/completions", post(chat))
            .with_state(Arc::clone(&state));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let addr = listener.local_addr()?;
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        Ok(Self { addr, state })
    }

    #[must_use]
    pub fn url(&self) -> String {
        format!("http://{}", self.addr)
    }

    /// Change the state (`healthy`, delays, `chat` ...).
    pub fn set(&self, change: impl FnOnce(&mut FakeState)) {
        if let Ok(mut state) = self.state.lock() {
            change(&mut state);
        }
    }

    /// Requests received so far: headers and body.
    #[must_use]
    pub fn seen(&self) -> Vec<(HeaderMap, Bytes)> {
        self.state.lock().map(|s| s.seen.clone()).unwrap_or_default()
    }
}

/// A localhost URL where nothing listens.
pub async fn closed_url() -> std::io::Result<String> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let addr = listener.local_addr()?;
    drop(listener);
    Ok(format!("http://{addr}"))
}

type Shared = State<Arc<Mutex<FakeState>>>;

fn snapshot<T>(state: &Arc<Mutex<FakeState>>, read: impl FnOnce(&FakeState) -> T) -> Option<T> {
    state.lock().ok().map(|s| read(&s))
}

async fn health(State(state): Shared) -> Response {
    let Some((healthy, delay)) = snapshot(&state, |s| (s.healthy, s.health_delay)) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    tokio::time::sleep(delay).await;
    if healthy {
        (StatusCode::OK, r#"{"status":"ok"}"#).into_response()
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            r#"{"error":{"code":503,"message":"Loading model"}}"#,
        )
            .into_response()
    }
}

async fn models_route(State(state): Shared) -> Response {
    let Some((models, status)) = snapshot(&state, |s| (s.models.clone(), s.models_status)) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let data: Vec<serde_json::Value> = models
        .iter()
        .map(|m| serde_json::json!({"id": m, "object": "model", "owned_by": "llamacpp"}))
        .collect();
    let body = serde_json::json!({"object": "list", "data": data}).to_string();
    (StatusCode::from_u16(status).unwrap_or(StatusCode::OK), body).into_response()
}

async fn chat(State(state): Shared, headers: HeaderMap, body: Bytes) -> Response {
    let Some((name, delay, behaviour)) = state.lock().ok().map(|mut s| {
        s.seen.push((headers, body));
        (s.name.clone(), s.chat_delay, s.chat.clone())
    }) else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    tokio::time::sleep(delay).await;
    match behaviour {
        ChatBehaviour::Ok => {
            let reply =
                serde_json::json!({"backend": name, "choices": [{"message": {"content": "Six pm."}}]});
            ([("content-type", "application/json")], reply.to_string()).into_response()
        }
        ChatBehaviour::Status(status, body) => (
            StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
            body,
        )
            .into_response(),
        ChatBehaviour::Stream(chunks, gap) => {
            let stream = futures_util::stream::unfold(chunks.into_iter(), move |mut rest| async move {
                let chunk = rest.next()?;
                tokio::time::sleep(gap).await;
                Some((Ok::<_, std::io::Error>(Bytes::from(chunk)), rest))
            });
            ([("content-type", "text/event-stream")], Body::from_stream(stream)).into_response()
        }
    }
}
