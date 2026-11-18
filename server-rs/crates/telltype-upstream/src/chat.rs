//! `POST /v1/chat/completions` through to the selected upstream.

use std::time::{Duration, Instant};

use bytes::Bytes;
use futures_util::stream::BoxStream;
use futures_util::{Stream, StreamExt};
use serde_json::Value;
use tokio::sync::OwnedSemaphorePermit;

use crate::error::UpstreamError;
use crate::set::Upstreams;

/// The upstream's successful reply.
pub struct ChatReply {
    /// Role of the upstream that answered (for logs).
    pub role: String,
    pub content_type: String,
    pub body: ReplyBody,
    /// Time until the reply's headers (streams) or its whole body (everything else), in ms.
    pub elapsed_ms: f64,
}

pub enum ReplyBody {
    Full(Bytes),
    /// Server-sent events as they arrive. The in-flight slot is held until the stream ends.
    Stream(BoxStream<'static, Result<Bytes, std::io::Error>>),
}

impl Upstreams {
    /// Validate, route and forward one chat request.
    pub async fn chat(&self, body: Bytes, request_id: &str) -> Result<ChatReply, UpstreamError> {
        let request = ChatRequest::parse(&body)?;
        let route = self.route(&request.model).await?;
        let upstream = &self.list[route.upstream];
        let spec = &upstream.spec;
        let forwarded = match &route.rewrite {
            None => body,
            Some(target) => request.with_model(target)?,
        };
        let permit = upstream
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| UpstreamError::Busy {
                role: spec.role.clone(),
                limit: spec.max_in_flight,
            })?;

        let started = Instant::now();
        let timeout_error = || UpstreamError::Timeout {
            role: spec.role.clone(),
            seconds: spec.timeout.as_secs(),
        };
        let send = self
            .client
            .post(format!("{}/v1/chat/completions", spec.url))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", request_id)
            .body(forwarded)
            .send();
        let response = match tokio::time::timeout(spec.timeout, send).await {
            Err(_) => return Err(timeout_error()),
            Ok(Err(e)) if e.is_connect() => {
                return Err(UpstreamError::NotReady(format!("{} is not reachable", spec.role)));
            }
            Ok(Err(e)) if e.is_timeout() => return Err(timeout_error()),
            Ok(Err(_)) => return Err(UpstreamError::Backend(format!("{} connection failed", spec.role))),
            Ok(Ok(response)) => response,
        };
        let status = response.status().as_u16();
        if status == 503 {
            return Err(UpstreamError::NotReady(format!("{} is loading", spec.role)));
        }
        if status != 200 {
            return Err(UpstreamError::Backend(format!(
                "{} returned HTTP {status}",
                spec.role
            )));
        }
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/json")
            .to_owned();
        if request.stream && content_type.starts_with("text/event-stream") {
            let elapsed_ms = started.elapsed().as_secs_f64() * 1000.0;
            let stream = idle_limited(response.bytes_stream(), spec.timeout, permit);
            return Ok(ChatReply {
                role: spec.role.clone(),
                content_type,
                body: ReplyBody::Stream(stream),
                elapsed_ms,
            });
        }
        let remaining = spec.timeout.saturating_sub(started.elapsed());
        let body = match tokio::time::timeout(remaining, response.bytes()).await {
            Err(_) => return Err(timeout_error()),
            Ok(Err(_)) => return Err(UpstreamError::Backend(format!("{} reply was cut off", spec.role))),
            Ok(Ok(body)) => body,
        };
        drop(permit);
        let elapsed_ms = started.elapsed().as_secs_f64() * 1000.0;
        Ok(ChatReply {
            role: spec.role.clone(),
            content_type,
            body: ReplyBody::Full(body),
            elapsed_ms,
        })
    }
}

/// End a stream with an error when the upstream sends nothing for `idle`; keep the slot until the
/// stream is finished or dropped.
fn idle_limited(
    stream: impl Stream<Item = Result<Bytes, reqwest::Error>> + Send + 'static,
    idle: Duration,
    permit: OwnedSemaphorePermit,
) -> BoxStream<'static, Result<Bytes, std::io::Error>> {
    let stream = Box::pin(stream);
    futures_util::stream::unfold(Some((stream, permit)), move |state| async move {
        let (mut stream, permit) = state?;
        match tokio::time::timeout(idle, stream.next()).await {
            Ok(Some(Ok(chunk))) => Some((Ok(chunk), Some((stream, permit)))),
            Ok(None) => None,
            Ok(Some(Err(e))) => Some((Err(std::io::Error::other(e.to_string())), None)),
            Err(_) => Some((
                Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "upstream went quiet",
                )),
                None,
            )),
        }
    })
    .boxed()
}

/// The parts of an `OpenAI` chat request the server checks; everything else passes through.
pub(crate) struct ChatRequest {
    pub(crate) model: String,
    pub(crate) stream: bool,
    payload: serde_json::Map<String, Value>,
}

impl ChatRequest {
    pub(crate) fn parse(body: &[u8]) -> Result<Self, UpstreamError> {
        let bad = |m: &str| UpstreamError::BadRequest(m.to_owned());
        let value: Value = serde_json::from_slice(body).map_err(|_| bad("body is not valid JSON"))?;
        let Value::Object(payload) = value else {
            return Err(bad("body must be a JSON object"));
        };
        let model = match payload.get("model") {
            Some(Value::String(m)) if !m.trim().is_empty() => m.clone(),
            _ => return Err(bad("model must be a non-empty string")),
        };
        let stream = match payload.get("stream") {
            None | Some(Value::Null) => false,
            Some(Value::Bool(b)) => *b,
            Some(_) => return Err(bad("stream must be true or false")),
        };
        match payload.get("messages") {
            Some(Value::Array(messages)) if !messages.is_empty() => {}
            _ => return Err(bad("messages must be a non-empty list")),
        }
        Ok(Self {
            model,
            stream,
            payload,
        })
    }

    pub(crate) fn with_model(&self, model: &str) -> Result<Bytes, UpstreamError> {
        let mut payload = self.payload.clone();
        payload.insert("model".to_owned(), Value::String(model.to_owned()));
        serde_json::to_vec(&payload)
            .map(Bytes::from)
            .map_err(|e| UpstreamError::Backend(format!("cannot encode the request: {e}")))
    }
}
