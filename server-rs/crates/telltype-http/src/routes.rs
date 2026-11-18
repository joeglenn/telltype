//! The routes: `/healthz`, `/v1/models`, `/v1/audio/transcriptions`, `/v1/chat/completions`.

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Instant;

use axum::body::Body;
use axum::extract::{Extension, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{Value, json};
use telltype_upstream::ReplyBody;

use crate::error::{ApiError, ErrorCode};
use crate::middleware::{RequestContext, around, read_capped};
use crate::multipart::{boundary, parse_parts};
use crate::state::AppState;

const FIELD_LIMIT_BYTES: usize = 64 * 1024;
const MIB: usize = 1024 * 1024;

type Context = Extension<Arc<RequestContext>>;

/// The whole API with its middleware.
pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/models", get(models))
        .route("/v1/audio/transcriptions", post(transcriptions))
        .route("/v1/chat/completions", post(chat))
        .fallback(|| async { ApiError::not_found() })
        .method_not_allowed_fallback(
            |method: Method| async move { ApiError::method_not_allowed(method.as_str()) },
        )
        .layer(axum::middleware::from_fn_with_state(state.clone(), around))
        .with_state(state)
}

async fn healthz(State(state): State<AppState>, Extension(context): Context) -> Response {
    let started = Instant::now();
    let (probes, models) = tokio::join!(state.upstreams.probe_all(), state.upstreams.models());
    context.timing("probe", started.elapsed().as_secs_f64() * 1000.0);
    let speech_ready = state.speech.ready();
    let ready = |role: &str| probes.iter().any(|p| p.role == role && p.ready);
    let status = match (speech_ready, ready("cleanup")) {
        (true, true) => "ok",
        (true, false) => "degraded",
        (false, _) => "not_ready",
    };
    let mut body = json!({
        "status": status,
        "version": crate::VERSION,
        "stt": {"ready": speech_ready, "model": state.speech_model, "engine": "sherpa-onnx"},
    });
    for probe in &probes {
        // Today's app reads the commands upstream under its old name, "assistant".
        let key = if probe.role == "commands" {
            "assistant"
        } else {
            probe.role.as_str()
        };
        body[key] = json!({"ready": probe.ready, "model": probe.model});
    }
    if body.get("assistant").is_none() {
        // No commands upstream configured: keep the field today's app reads, marked not ready.
        body["assistant"] = json!({"ready": false, "model": ""});
    }
    let mut lists = BTreeMap::from([("speech".to_owned(), json!([state.speech_model]))]);
    for (role, ids) in models.per_role {
        lists.insert(role, ids.map_or(Value::Null, |ids| json!(ids)));
    }
    body["models"] = json!(lists);
    if speech_ready {
        return Json(body).into_response();
    }
    let error = ApiError::not_ready("speech recognition is not ready", state.limits.retry_after_s);
    body["error"] = error.body()["error"].clone();
    let mut response = (StatusCode::SERVICE_UNAVAILABLE, Json(body)).into_response();
    response
        .headers_mut()
        .insert(header::RETRY_AFTER, HeaderValue::from(state.limits.retry_after_s));
    response.extensions_mut().insert(ErrorCode(error.code));
    response
}

async fn models(State(state): State<AppState>) -> Json<Value> {
    let mut data = vec![json!({
        "id": state.speech_model, "object": "model", "created": 0, "owned_by": "telltype"
    })];
    data.extend(state.upstreams.models().await.data);
    Json(json!({"object": "list", "data": data}))
}

async fn transcriptions(
    State(state): State<AppState>,
    Extension(context): Context,
    headers: HeaderMap,
    body: Body,
) -> Result<Response, ApiError> {
    let retry = state.limits.retry_after_s;
    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if !content_type
        .to_ascii_lowercase()
        .starts_with("multipart/form-data")
    {
        return Err(ApiError::bad_request("expected a multipart/form-data upload"));
    }
    let boundary = boundary(content_type)
        .ok_or_else(|| ApiError::bad_request("malformed multipart body: no boundary"))?;
    context.backend("speech");
    let admission = state
        .speech
        .admit()
        .map_err(|e| ApiError::from_speech(&e, retry))?;
    let max = state.limits.max_upload_bytes;
    let too_large = format!("upload is larger than {} MB", max / MIB);
    let raw = read_capped(
        &headers,
        body,
        max + state.limits.multipart_overhead_bytes,
        &too_large,
    )
    .await?;
    context.bytes_in(raw.len());
    let form = Form::read(&raw, &boundary, max, &too_large)?;

    let transcript = state
        .speech
        .transcribe(
            &admission,
            form.file,
            form.fields.get("prompt").map(String::as_str),
        )
        .await
        .map_err(|e| ApiError::from_speech(&e, retry))?;
    context.timing("decode", transcript.timings.decode_ms);
    context.timing("queue", transcript.timings.queue_ms);
    context.timing("stt", transcript.timings.stt_ms);
    Ok(match form.response_format {
        Format::Text => (
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            transcript.text,
        )
            .into_response(),
        Format::Json => {
            Json(json!({"text": transcript.text, "duration": transcript.duration_s})).into_response()
        }
        Format::VerboseJson => {
            Json(json!({"text": transcript.text, "duration": transcript.duration_s, "language": "en"}))
                .into_response()
        }
    })
}

enum Format {
    Json,
    VerboseJson,
    Text,
}

struct Form {
    file: bytes::Bytes,
    fields: BTreeMap<String, String>,
    response_format: Format,
}

impl Form {
    /// The upload's parts checked as the contract says: `file` required and within the limit,
    /// other fields UTF-8 and at most 64 KiB, `language` only `en`, `response_format` json, text or
    /// `verbose_json`. Unknown fields (`model`, `temperature` ...) are accepted and ignored.
    fn read(raw: &bytes::Bytes, boundary: &str, max_file: usize, too_large: &str) -> Result<Self, ApiError> {
        let mut file = None;
        let mut fields = BTreeMap::new();
        for part in parse_parts(raw, boundary)? {
            if part
                .content_type
                .as_deref()
                .is_some_and(|t| t.to_ascii_lowercase().starts_with("multipart/"))
            {
                return Err(ApiError::bad_request("nested multipart bodies are not accepted"));
            }
            let Some(name) = part.name else { continue };
            if name == "file" {
                if part.data.len() > max_file {
                    return Err(ApiError::too_large(too_large));
                }
                file = Some(raw.slice(part.data));
            } else {
                if part.data.len() > FIELD_LIMIT_BYTES {
                    return Err(ApiError::bad_request(format!("field '{name}' is too long")));
                }
                let value = std::str::from_utf8(&raw[part.data])
                    .map_err(|_| ApiError::bad_request(format!("field '{name}' is not UTF-8")))?;
                fields.insert(name, value.trim().to_owned());
            }
        }
        let file = file
            .filter(|f| !f.is_empty())
            .ok_or_else(|| ApiError::bad_request("missing or empty 'file' field"))?;
        if let Some(language) = fields.get("language").filter(|l| !l.is_empty())
            && !language.eq_ignore_ascii_case("en")
        {
            return Err(ApiError::bad_request("only language 'en' is supported"));
        }
        let response_format = match fields.get("response_format").map(String::as_str) {
            None | Some("" | "json") => Format::Json,
            Some("verbose_json") => Format::VerboseJson,
            Some("text") => Format::Text,
            Some(_) => {
                return Err(ApiError::bad_request(
                    "response_format must be one of json, text, verbose_json",
                ));
            }
        };
        Ok(Self {
            file,
            fields,
            response_format,
        })
    }
}

async fn chat(
    State(state): State<AppState>,
    Extension(context): Context,
    headers: HeaderMap,
    body: Body,
) -> Result<Response, ApiError> {
    let max = state.limits.max_json_bytes;
    let too_large = format!("request body is larger than {} MB", max.div_ceil(MIB));
    let raw = read_capped(&headers, body, max, &too_large).await?;
    context.bytes_in(raw.len());
    let reply = state
        .upstreams
        .chat(raw, &context.request_id)
        .await
        .map_err(|e| ApiError::from_upstream(&e, state.limits.retry_after_s))?;
    context.backend(&reply.role);
    context.timing("llm", reply.elapsed_ms);
    let body = match reply.body {
        ReplyBody::Full(bytes) => Body::from(bytes),
        ReplyBody::Stream(stream) => Body::from_stream(stream),
    };
    let content_type =
        HeaderValue::from_str(&reply.content_type).unwrap_or(HeaderValue::from_static("application/json"));
    Ok(([(header::CONTENT_TYPE, content_type)], body).into_response())
}
