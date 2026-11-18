//! Around every request: request id, key check, `Server-Timing`, `Server`, and one log line.

use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::body::{Body, HttpBody};
use axum::extract::{ConnectInfo, Request, State};
use axum::http::{HeaderMap, HeaderValue, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use crate::error::{ApiError, ErrorCode};
use crate::keys::Identity;
use crate::state::{AppState, HttpLimits};

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
    let peer = request
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0.ip());
    let client = peer.map(|peer| state.guard.client(peer, request.headers()));
    let method = request.method().to_string();
    let path = request.uri().path().to_owned();

    let (mut response, key_name) = match admit(&state, client, &path, request.headers()) {
        Err(error) => (error.into_response(), None),
        Ok(identity) => {
            let name = identity.as_ref().map(|i| i.name.clone());
            if let Some(identity) = identity {
                request.extensions_mut().insert(identity);
            }
            (next.run(request).await, name)
        }
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
    // Metadata only: never bodies, transcripts, prompts, replies or the key (only its name).
    tracing::info!(
        target: "telltype::request",
        request_id = %context.request_id,
        client = client.map(|c| c.to_string()).as_deref().unwrap_or(""),
        key = key_name.as_deref().unwrap_or(""),
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

fn whole_seconds(wait: std::time::Duration) -> u64 {
    wait.as_secs() + u64::from(wait.subsec_nanos() > 0)
}

/// Everything decided before the body is touched, in this order: the client's request rate, a
/// block after too many failed keys (429, no key check at all), the key (401), the declared size
/// (413). Each rejection closes the connection instead of reading the body.
fn admit(
    state: &AppState,
    client: Option<IpAddr>,
    path: &str,
    headers: &HeaderMap,
) -> Result<Option<Identity>, ApiError> {
    if let Some(client) = client {
        state.guard.request(client).map_err(|wait| {
            ApiError::rate_limited("too many requests from this address", whole_seconds(wait)).closing()
        })?;
        if let Some(wait) = state.guard.auth_blocked(client) {
            return Err(ApiError::rate_limited(
                "too many failed keys from this address",
                whole_seconds(wait),
            )
            .closing());
        }
    }
    let identity = match &state.keys {
        None => None,
        Some(keys) => {
            let identity = bearer(headers).and_then(|token| keys.check(token));
            if identity.is_none() {
                if let Some(client) = client {
                    state.guard.auth_failed(client);
                }
                return Err(ApiError::unauthorized().closing());
            }
            identity
        }
    };
    let cap = state.limits.body_cap(path);
    if declared_length(headers).is_some_and(|n| n > cap) {
        return Err(ApiError::too_large(format!("request body is larger than {cap} bytes")).closing());
    }
    Ok(identity)
}

/// The token of `Authorization: Bearer <token>` (scheme case-insensitive).
fn bearer(headers: &HeaderMap) -> Option<&[u8]> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = value.split_once(' ')?;
    scheme
        .eq_ignore_ascii_case("bearer")
        .then(|| token.trim().as_bytes())
        .filter(|t| !t.is_empty())
}

fn declared_length(headers: &HeaderMap) -> Option<usize> {
    headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok())
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
/// Nothing above the cap is read: the error closes the connection (`Connection: close`). A client
/// that is still sending may then see the connection reset before the 413 (clients that send
/// `Expect: 100-continue` see the 413 cleanly). A body that falls behind `body_min_bytes_per_s`
/// after `body_grace` ends with 408 `request_timeout` and a closed connection.
pub(crate) async fn read_capped(
    headers: &HeaderMap,
    body: Body,
    cap: usize,
    too_large: &str,
    limits: &HttpLimits,
) -> Result<bytes::Bytes, ApiError> {
    use futures_util::StreamExt;
    let declared = declared_length(headers);
    if declared.is_some_and(|n| n > cap) {
        return Err(ApiError::too_large(too_large).closing());
    }
    let started = tokio::time::Instant::now();
    let mut stream = body.into_data_stream();
    // A declared length is a claim: allocate at most 1 MiB ahead of the bytes that really came.
    let mut collected = Vec::with_capacity(declared.unwrap_or(0).min(cap).min(1 << 20));
    loop {
        let next = match body_deadline(started, collected.len(), limits) {
            Some(deadline) => tokio::time::timeout_at(deadline, stream.next())
                .await
                .map_err(|_| ApiError::request_timeout("the request body arrived too slowly").closing())?,
            None => stream.next().await,
        };
        let Some(chunk) = next else { break };
        let chunk =
            chunk.map_err(|_| ApiError::bad_request("the request body could not be read").closing())?;
        if collected.len() + chunk.len() > cap {
            return Err(ApiError::too_large(too_large).closing());
        }
        collected.extend_from_slice(&chunk);
    }
    Ok(bytes::Bytes::from(collected))
}

/// When the next chunk must have arrived: the grace plus the time `received` bytes take at the
/// minimum rate. `None`: no minimum rate.
fn body_deadline(
    started: tokio::time::Instant,
    received: usize,
    limits: &HttpLimits,
) -> Option<tokio::time::Instant> {
    if limits.body_min_bytes_per_s == 0 {
        return None;
    }
    let allowance = Duration::try_from_secs_f64(received as f64 / limits.body_min_bytes_per_s as f64).ok()?;
    started.checked_add(limits.body_grace)?.checked_add(allowance)
}

#[cfg(test)]
mod tests {
    use axum::http::{HeaderMap, HeaderValue};

    use super::{bearer, request_id, whole_seconds};

    #[test]
    fn bearer_tokens() {
        let with = |v: &str| {
            let mut h = HeaderMap::new();
            h.insert("authorization", HeaderValue::from_str(v).unwrap());
            h
        };
        assert_eq!(bearer(&with("Bearer abc")), Some(&b"abc"[..]));
        assert_eq!(bearer(&with("bEaReR  abc ")), Some(&b"abc"[..]));
        assert_eq!(bearer(&with("Bearer")), None);
        assert_eq!(bearer(&with("Bearer ")), None);
        assert_eq!(bearer(&with("Basic abc")), None);
        assert_eq!(bearer(&HeaderMap::new()), None);
        assert_eq!(whole_seconds(std::time::Duration::from_millis(5001)), 6);
        assert_eq!(whole_seconds(std::time::Duration::from_secs(5)), 5);
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
