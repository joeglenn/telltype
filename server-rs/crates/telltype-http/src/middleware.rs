//! Around every request: request id, key check, `Server-Timing`, `Server`, and one log line.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use axum::body::{Body, HttpBody};
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{HeaderMap, HeaderValue, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use subtle::ConstantTimeEq;

use crate::error::{ApiError, ErrorCode};
use crate::state::AppState;

/// Per-request facts the handlers add to, read once the response is ready.
#[derive(Debug, Default)]
pub(crate) struct RequestContext {
    pub(crate) request_id: String,
    inner: Mutex<ContextInner>,
}

#[derive(Debug, Default)]
struct ContextInner {
    timings: Vec<String>,
    backend: Option<String>,
    bytes_in: usize,
}

impl RequestContext {
    pub(crate) fn timing(&self, name: &str, ms: f64) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.timings.push(format!("{name};dur={ms:.1}"));
        }
    }
    pub(crate) fn backend(&self, name: &str) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.backend = Some(name.to_owned());
        }
    }
    pub(crate) fn bytes_in(&self, bytes: usize) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.bytes_in = bytes;
        }
    }
}

pub(crate) const SERVER_NAME: &str = concat!("telltype-server/", env!("CARGO_PKG_VERSION"));

pub(crate) async fn around(State(state): State<AppState>, mut request: Request, next: Next) -> Response {
    let started = Instant::now();
    let context = Arc::new(RequestContext {
        request_id: request_id(request.headers().get("x-request-id")),
        ..RequestContext::default()
    });
    request.extensions_mut().insert(Arc::clone(&context));
    let client = request
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0.ip().to_string());
    let method = request.method().to_string();
    let path = request.uri().path().to_owned();

    let mut response = if authorised(state.key.as_deref().map(Vec::as_slice), request.headers()) {
        next.run(request).await
    } else {
        ApiError::unauthorized().into_response()
    };

    let total_ms = started.elapsed().as_secs_f64() * 1000.0;
    let (timing, backend, bytes_in) = context.inner.lock().map_or_else(
        |_| (String::new(), None, 0),
        |inner| {
            let mut entries = inner.timings.clone();
            entries.push(format!("gate;dur={total_ms:.1}"));
            (entries.join(", "), inner.backend.clone(), inner.bytes_in)
        },
    );
    let headers = response.headers_mut();
    if let Ok(value) = HeaderValue::from_str(&context.request_id) {
        headers.insert("x-request-id", value);
    }
    if let Ok(value) = HeaderValue::from_str(&timing) {
        headers.insert("server-timing", value);
    }
    headers.insert(header::SERVER, HeaderValue::from_static(SERVER_NAME));
    let code = response.extensions().get::<ErrorCode>().map(|c| c.0);
    let bytes_out = response.body().size_hint().exact();
    // Metadata only: never bodies, transcripts, prompts, replies or the key.
    tracing::info!(
        target: "telltype::request",
        request_id = %context.request_id,
        client = client.as_deref().unwrap_or(""),
        method = %method,
        path = %path,
        status = response.status().as_u16(),
        code = code.unwrap_or(""),
        backend = backend.as_deref().unwrap_or(""),
        ms = (total_ms * 10.0).round() / 10.0,
        timing = %timing,
        bytes_in,
        bytes_out = bytes_out.unwrap_or(0),
        "request"
    );
    response
}

/// `Authorization: Bearer <key>`, scheme case-insensitive, key compared in constant time.
fn authorised(key: Option<&[u8]>, headers: &HeaderMap) -> bool {
    let Some(key) = key else { return true };
    let Some(value) = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    let Some((scheme, token)) = value.split_once(' ') else {
        return false;
    };
    if !scheme.eq_ignore_ascii_case("bearer") {
        return false;
    }
    let token = token.trim().as_bytes();
    token.len() == key.len() && bool::from(token.ct_eq(key))
}

/// The client's id if it is 1 to 128 of `A-Za-z0-9._:-`, else a new random one (32 hex digits).
fn request_id(header: Option<&HeaderValue>) -> String {
    header
        .and_then(|v| v.to_str().ok())
        .filter(|id| {
            (1..=128).contains(&id.len())
                && id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':' | b'-'))
        })
        .map_or_else(|| uuid::Uuid::new_v4().simple().to_string(), str::to_owned)
}

/// Read a body up to `cap` bytes; a declared or actual size above it is 413 with `too_large`.
///
/// An over-long body is still read (and discarded, up to [`DRAIN_LIMIT`] within [`DRAIN_TIMEOUT`])
/// before the 413 goes out: a client that is still uploading would otherwise see the connection
/// close under it ("broken pipe") instead of the error.
pub(crate) async fn read_capped(
    headers: &HeaderMap,
    body: Body,
    cap: usize,
    too_large: &str,
) -> Result<bytes::Bytes, ApiError> {
    use futures_util::StreamExt;
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok());
    let mut stream = body.into_data_stream();
    if declared.is_some_and(|n| n > cap) {
        drain(&mut stream, 0).await;
        return Err(ApiError::too_large(too_large));
    }
    let mut collected = Vec::with_capacity(declared.unwrap_or(0));
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| ApiError::bad_request("the request body could not be read"))?;
        if collected.len() + chunk.len() > cap {
            let seen = collected.len() + chunk.len();
            drop(collected);
            drain(&mut stream, seen).await;
            return Err(ApiError::too_large(too_large));
        }
        collected.extend_from_slice(&chunk);
    }
    Ok(bytes::Bytes::from(collected))
}

/// Bodies above the limit are read and thrown away up to this many bytes in total ...
const DRAIN_LIMIT: usize = 64 * 1024 * 1024;
/// ... and for at most this long; after that the connection is closed.
const DRAIN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

async fn drain(stream: &mut axum::body::BodyDataStream, already: usize) {
    use futures_util::StreamExt;
    let mut total = already;
    let _ = tokio::time::timeout(DRAIN_TIMEOUT, async {
        while total <= DRAIN_LIMIT {
            match stream.next().await {
                Some(Ok(chunk)) => total += chunk.len(),
                _ => break,
            }
        }
    })
    .await;
}

#[cfg(test)]
mod tests {
    use axum::http::{HeaderMap, HeaderValue};

    use super::{authorised, request_id};

    #[test]
    fn key_check() {
        let key = b"k".repeat(40);
        let with = |v: &str| {
            let mut h = HeaderMap::new();
            h.insert("authorization", HeaderValue::from_str(v).unwrap());
            h
        };
        let good = format!("Bearer {}", "k".repeat(40));
        assert!(authorised(Some(&key), &with(&good)));
        assert!(authorised(Some(&key), &with(&good.replace("Bearer", "bEaReR"))));
        assert!(!authorised(Some(&key), &with(&format!("{good}x"))));
        assert!(!authorised(Some(&key), &with("Bearer")));
        assert!(!authorised(Some(&key), &with(&good.replace("Bearer", "Basic"))));
        assert!(!authorised(Some(&key), &HeaderMap::new()));
        assert!(authorised(None, &HeaderMap::new()), "open server");
    }

    #[test]
    fn request_ids() {
        assert_eq!(
            request_id(Some(&HeaderValue::from_static("session-42:a.b_c"))),
            "session-42:a.b_c"
        );
        for bad in ["", "bad id", "evil\u{e9}"] {
            let header = HeaderValue::from_bytes(bad.as_bytes()).unwrap();
            assert_eq!(request_id(Some(&header)).len(), 32, "{bad}");
        }
        assert_eq!(
            request_id(Some(&HeaderValue::from_str(&"x".repeat(129)).unwrap())).len(),
            32
        );
        assert_eq!(request_id(None).len(), 32);
    }
}
