//! Every error the API returns, and the one place where the other crates' errors become HTTP
//! statuses and contract codes (`docs/contract.md`, "Errors").

use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use telltype_speech::{DecodeError, SpeechError};
use telltype_upstream::UpstreamError;

use crate::multipart::MultipartError;

/// An error response: `{"error": {"code": ..., "message": ...}}`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: &'static str,
    pub message: String,
    /// Seconds for `Retry-After` (503 responses).
    pub retry_after: Option<u64>,
}

/// Put on every error response so the request log can name the code.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ErrorCode(pub &'static str);

impl ApiError {
    fn new(status: StatusCode, code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status,
            code,
            message: message.into(),
            retry_after: None,
        }
    }

    #[must_use]
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, "bad_request", message)
    }
    #[must_use]
    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "unauthorized", "missing or wrong key")
    }
    #[must_use]
    pub fn not_found() -> Self {
        Self::new(StatusCode::NOT_FOUND, "not_found", "no such route")
    }
    #[must_use]
    pub fn method_not_allowed(method: &str) -> Self {
        Self::new(
            StatusCode::METHOD_NOT_ALLOWED,
            "method_not_allowed",
            format!("method {method} is not allowed here"),
        )
    }
    #[must_use]
    pub fn too_large(message: impl Into<String>) -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, "too_large", message)
    }
    #[must_use]
    pub fn unsupported_audio(message: impl Into<String>) -> Self {
        Self::new(StatusCode::UNSUPPORTED_MEDIA_TYPE, "unsupported_audio", message)
    }
    #[must_use]
    pub fn busy(message: impl Into<String>) -> Self {
        Self::new(StatusCode::TOO_MANY_REQUESTS, "busy", message)
    }
    #[must_use]
    pub fn backend_error(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_GATEWAY, "backend_error", message)
    }
    #[must_use]
    pub fn not_ready(message: impl Into<String>, retry_after_s: u64) -> Self {
        Self {
            retry_after: Some(retry_after_s),
            ..Self::new(StatusCode::SERVICE_UNAVAILABLE, "not_ready", message)
        }
    }
    #[must_use]
    pub fn backend_timeout(message: impl Into<String>) -> Self {
        Self::new(StatusCode::GATEWAY_TIMEOUT, "backend_timeout", message)
    }
    #[must_use]
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "internal_error", message)
    }

    /// Speech errors: busy 429, loading 503, decoding 413/415, recognition timeout 504.
    #[must_use]
    pub fn from_speech(error: &SpeechError, retry_after_s: u64) -> Self {
        let message = error.to_string();
        match error {
            SpeechError::NotReady => Self::not_ready(message, retry_after_s),
            SpeechError::Busy { .. } => Self::busy(message),
            SpeechError::Decode(DecodeError::TooLong { .. }) => Self::too_large(message),
            SpeechError::Decode(DecodeError::Unsupported(_) | DecodeError::Empty) => {
                Self::unsupported_audio(message)
            }
            SpeechError::Timeout { .. } => Self::backend_timeout(message),
            SpeechError::Recognize(_) => Self::internal(message),
        }
    }

    /// Upstream errors: 400, 429, 503 (unreachable or loading), 504, 502 for anything else.
    #[must_use]
    pub fn from_upstream(error: &UpstreamError, retry_after_s: u64) -> Self {
        let message = error.to_string();
        match error {
            UpstreamError::BadRequest(_) => Self::bad_request(message),
            UpstreamError::Busy { .. } => Self::busy(message),
            UpstreamError::NotReady(_) => Self::not_ready(message, retry_after_s),
            UpstreamError::Timeout { .. } => Self::backend_timeout(message),
            UpstreamError::Backend(_) | UpstreamError::Client(_) => Self::backend_error(message),
        }
    }

    /// The JSON body as a value, for responses that add fields (`/healthz`).
    #[must_use]
    pub fn body(&self) -> serde_json::Value {
        serde_json::json!({"error": {"code": self.code, "message": self.message}})
    }
}

impl From<MultipartError> for ApiError {
    fn from(error: MultipartError) -> Self {
        Self::bad_request(format!("malformed multipart body: {error}"))
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut response = (self.status, axum::Json(self.body())).into_response();
        let headers = response.headers_mut();
        if let Some(seconds) = self.retry_after {
            headers.insert(header::RETRY_AFTER, HeaderValue::from(seconds));
        }
        if self.status == StatusCode::UNAUTHORIZED {
            headers.insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
        }
        response.extensions_mut().insert(ErrorCode(self.code));
        response
    }
}

#[cfg(test)]
mod tests {
    use axum::http::StatusCode;
    use telltype_speech::{DecodeError, RecognizeError, SpeechError};
    use telltype_upstream::UpstreamError;

    use super::ApiError;

    #[test]
    fn speech_errors_map_to_the_contract() {
        let cases = [
            (SpeechError::NotReady, 503, "not_ready"),
            (
                SpeechError::Busy {
                    in_flight: 6,
                    limit: 6,
                },
                429,
                "busy",
            ),
            (
                SpeechError::Decode(DecodeError::TooLong { minutes: "6".into() }),
                413,
                "too_large",
            ),
            (
                SpeechError::Decode(DecodeError::Unsupported("x".into())),
                415,
                "unsupported_audio",
            ),
            (SpeechError::Decode(DecodeError::Empty), 415, "unsupported_audio"),
            (SpeechError::Timeout { seconds: 120 }, 504, "backend_timeout"),
            (
                SpeechError::Recognize(RecognizeError::NoResult),
                500,
                "internal_error",
            ),
        ];
        for (error, status, code) in cases {
            let api = ApiError::from_speech(&error, 5);
            assert_eq!((api.status.as_u16(), api.code), (status, code));
            assert_eq!(api.retry_after.is_some(), status == 503);
        }
    }

    #[test]
    fn upstream_errors_map_to_the_contract() {
        let cases = [
            (UpstreamError::BadRequest("x".into()), 400, "bad_request"),
            (
                UpstreamError::Busy {
                    role: "cleanup".into(),
                    limit: 4,
                },
                429,
                "busy",
            ),
            (UpstreamError::NotReady("x".into()), 503, "not_ready"),
            (
                UpstreamError::Timeout {
                    role: "cleanup".into(),
                    seconds: 60,
                },
                504,
                "backend_timeout",
            ),
            (UpstreamError::Backend("x".into()), 502, "backend_error"),
            (UpstreamError::Client("x".into()), 502, "backend_error"),
        ];
        for (error, status, code) in cases {
            let api = ApiError::from_upstream(&error, 5);
            assert_eq!((api.status.as_u16(), api.code), (status, code));
        }
        assert_eq!(ApiError::internal("x").status, StatusCode::INTERNAL_SERVER_ERROR);
    }
}
