#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! Telltype Server's HTTP API against fake upstreams and a fake recogniser (no models, no GPU).
//! Covers auth, limits, the contract's error codes, health, routing and timings.

mod common;

use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum::body::Body;
use axum::http::Request;
use bytes::Bytes;
use common::*;
use serde_json::{Value, json};
use telltype_http::{AppState, HttpLimits, router};
use telltype_speech::EngineLimits;
use telltype_speech::testing::{FakeRecognizer, open_gate, wav_tone};
use telltype_upstream::Upstreams;
use telltype_upstream::testing::{ChatBehaviour, closed_url};
use tower::ServiceExt;

// Auth

#[tokio::test]
async fn every_route_needs_the_key() {
    let h = harness(Options::default()).await;
    let variants = [
        None,
        Some("Bearer wrong-key".to_owned()),
        Some(format!("Basic {KEY}")),
        Some("Bearer".to_owned()),
        Some(format!("Bearer {KEY}x")),
    ];
    for auth in variants {
        for (method, path) in [
            ("GET", "/healthz"),
            ("POST", "/v1/audio/transcriptions"),
            ("POST", "/v1/chat/completions"),
            ("GET", "/v1/models"),
            ("GET", "/does-not-exist"),
        ] {
            let mut request = Request::builder().method(method).uri(path);
            if let Some(auth) = &auth {
                request = request.header("authorization", auth);
            }
            let (status, headers, body) = send(&h.app, request.body(Body::empty()).unwrap()).await;
            assert_eq!(status, 401, "{path} {auth:?}");
            assert_eq!(headers["www-authenticate"], "Bearer");
            assert_eq!(code(&body), "unauthorized");
        }
    }
    assert!(h.cleanup.seen().is_empty() && h.commands.seen().is_empty() && h.recognizer.calls().is_empty());
}

#[tokio::test]
async fn scheme_is_case_insensitive_and_open_mode_needs_no_key() {
    let h = harness(Options::default()).await;
    let request = Request::get("/healthz")
        .header("authorization", format!("bearer {KEY}"))
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&h.app, request).await.0, 200);
    let open = harness(Options {
        key: false,
        ..Options::default()
    })
    .await;
    assert_eq!(
        send(&open.app, Request::get("/healthz").body(Body::empty()).unwrap())
            .await
            .0,
        200
    );
}

#[tokio::test]
async fn unknown_route_and_wrong_method_after_auth() {
    let h = harness(Options::default()).await;
    let (status, _, body) = send(&h.app, get("/nope")).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (404, "not_found"));
    let (status, _, body) = send(&h.app, get("/v1/chat/completions")).await;
    assert_eq!(
        (status.as_u16(), code(&body).as_str()),
        (405, "method_not_allowed")
    );
}

// /healthz

#[tokio::test]
async fn healthz_ok_with_model_lists() {
    let h = harness(Options::default()).await;
    let (status, headers, body) = send(&h.app, get("/healthz")).await;
    assert_eq!(status, 200);
    assert_eq!(
        json_of(&body),
        json!({
            "status": "ok",
            "version": telltype_http::VERSION,
            "stt": {"ready": true, "model": "model-stt", "engine": "sherpa-onnx"},
            "cleanup": {"ready": true, "model": "model-cleanup"},
            "assistant": {"ready": true, "model": "model-commands"},
            "models": {"speech": ["model-stt"], "cleanup": ["model-cleanup"], "commands": ["qwen3.5-4b-q4_k_m", "gemma-4-12b"]},
        })
    );
    let timing = headers["server-timing"].to_str().unwrap();
    assert!(
        timing.starts_with("probe;dur=") && timing.contains(", gate;dur="),
        "{timing}"
    );
    assert_eq!(
        headers["server"],
        format!("telltype-server/{}", telltype_http::VERSION)
    );
}

#[tokio::test]
async fn healthz_degraded_without_cleanup_and_ok_without_assistant() {
    let h = harness(Options::default()).await;
    h.cleanup.set(|s| s.healthy = false);
    let body = json_of(&send(&h.app, get("/healthz")).await.2);
    assert_eq!(
        (body["status"].as_str(), body["cleanup"]["ready"].as_bool()),
        (Some("degraded"), Some(false))
    );
    h.cleanup.set(|s| {
        s.healthy = true;
        s.health_delay = Duration::from_secs(1);
    });
    assert_eq!(
        json_of(&send(&h.app, get("/healthz")).await.2)["status"],
        "degraded",
        "a slow probe is not ready"
    );
    h.cleanup.set(|s| s.health_delay = Duration::ZERO);
    h.commands.set(|s| s.healthy = false);
    let body = json_of(&send(&h.app, get("/healthz")).await.2);
    assert_eq!(
        (body["status"].as_str(), body["assistant"]["ready"].as_bool()),
        (Some("ok"), Some(false))
    );
}

#[tokio::test]
async fn healthz_503_while_speech_loads() {
    let h = harness(Options {
        ready: false,
        ..Options::default()
    })
    .await;
    let (status, headers, body) = send(&h.app, get("/healthz")).await;
    assert_eq!(status, 503);
    assert_eq!(headers["retry-after"], "5");
    let body = json_of(&body);
    assert_eq!(
        (body["status"].as_str(), body["stt"]["ready"].as_bool()),
        (Some("not_ready"), Some(false))
    );
    assert_eq!(body["error"]["code"], "not_ready");
}

#[tokio::test]
async fn healthz_with_every_upstream_down() {
    let h = harness(Options::default()).await;
    let dead = closed_url().await.unwrap();
    let upstreams = Upstreams::new(
        vec![
            spec("cleanup", dead.clone(), ("cleanup", "cleanup"), 1),
            spec("commands", dead, ("assistant", "q"), 1),
        ],
        Duration::from_millis(200),
    )
    .unwrap();
    let state = AppState {
        key: None,
        speech: h.engine.clone(),
        speech_model: "m".into(),
        upstreams: Arc::new(upstreams),
        limits: Options::default().limits,
    };
    let app = router(state);
    let body = json_of(&send(&app, get("/healthz")).await.2);
    assert_eq!(body["status"], "degraded");
    assert_eq!(body["models"]["cleanup"], Value::Null);
    let (status, headers, body) = send(&app, chat_request(chat_json("cleanup"))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (503, "not_ready"));
    assert_eq!(headers["retry-after"], "5");
}

// Transcription

#[tokio::test]
async fn transcribes_with_timings_request_id_and_formats() {
    let h = harness(Options::default()).await;
    let mut request = upload(form(
        Some(&wav_tone(1.5, 16_000, 1)),
        &[("model", "whisper-1"), ("prompt", "Hyrule")],
    ));
    request
        .headers_mut()
        .insert("x-request-id", "session-42".parse().unwrap());
    let (status, headers, body) = send(&h.app, request).await;
    assert_eq!(status, 200);
    assert_eq!(
        json_of(&body),
        json!({"text": SECRET_TRANSCRIPT, "duration": 1.5})
    );
    assert_eq!(headers["x-request-id"], "session-42");
    let timing = headers["server-timing"].to_str().unwrap();
    let names: Vec<_> = timing.split(", ").map(|e| e.split(';').next().unwrap()).collect();
    assert_eq!(names, ["decode", "queue", "stt", "gate"], "{timing}");
    assert_eq!(h.recognizer.calls(), [(24_000, vec![])]);

    let (_, _, body) = send(
        &h.app,
        upload(form(
            Some(&wav_tone(0.5, 16_000, 1)),
            &[("response_format", "verbose_json"), ("language", "en")],
        )),
    )
    .await;
    assert_eq!(
        json_of(&body),
        json!({"text": SECRET_TRANSCRIPT, "duration": 0.5, "language": "en"})
    );
    let (_, headers, body) = send(
        &h.app,
        upload(form(
            Some(&wav_tone(0.5, 16_000, 1)),
            &[("response_format", "text")],
        )),
    )
    .await;
    assert_eq!(
        (headers["content-type"].to_str().unwrap(), body.as_ref()),
        ("text/plain; charset=utf-8", SECRET_TRANSCRIPT.as_bytes())
    );
}

#[tokio::test]
async fn resamples_stereo_44k_to_16k_mono() {
    let h = harness(Options::default()).await;
    let (status, _, body) = send(&h.app, upload(form(Some(&wav_tone(1.0, 44_100, 2)), &[]))).await;
    assert_eq!(status, 200);
    assert_eq!(json_of(&body)["duration"], 1.0);
    let samples = h.recognizer.calls()[0].0;
    assert!((15_900..=16_100).contains(&samples), "{samples}");
}

#[tokio::test]
async fn prompt_terms_become_hotwords_when_enabled() {
    let options = Options {
        engine: EngineLimits {
            max_hotwords: Some(100),
            ..Options::default().engine
        },
        ..Options::default()
    };
    let h = harness(options).await;
    send(
        &h.app,
        upload(form(
            Some(&wav_tone(0.5, 16_000, 1)),
            &[("prompt", "Kubernetes, kubernetes , Hyrule:3,,Igor")],
        )),
    )
    .await;
    assert_eq!(h.recognizer.calls()[0].1, ["Kubernetes", "Hyrule 3", "Igor"]);
}

#[tokio::test]
async fn bad_uploads_are_typed_errors() {
    let h = harness(Options::default()).await;
    let wav = wav_tone(0.5, 16_000, 1);
    let long_field = "x".repeat(70 * 1024);
    let cases: Vec<(Vec<u8>, u16, &str, &str)> = vec![
        (
            form(Some(&wav), &[("response_format", "srt")]),
            400,
            "bad_request",
            "response_format",
        ),
        (
            form(Some(&wav), &[("language", "fr")]),
            400,
            "bad_request",
            "language",
        ),
        (form(None, &[("language", "en")]), 400, "bad_request", "'file'"),
        (form(Some(b""), &[]), 400, "bad_request", "'file'"),
        (
            form(Some(&wav), &[("prompt", &long_field)]),
            400,
            "bad_request",
            "too long",
        ),
        (
            form(Some(b"definitely not audio"), &[]),
            415,
            "unsupported_audio",
            "decode",
        ),
        (
            form(Some(&wav_tone(0.0, 16_000, 1)), &[]),
            415,
            "unsupported_audio",
            "no audio",
        ),
        (
            b"garbage without boundaries".to_vec(),
            400,
            "bad_request",
            "malformed multipart",
        ),
    ];
    for (body, status, expected, message) in cases {
        let (got, _, body) = send(&h.app, upload(body)).await;
        let parsed = json_of(&body);
        assert_eq!(
            (got.as_u16(), parsed["error"]["code"].as_str().unwrap()),
            (status, expected),
            "{parsed}"
        );
        assert!(
            parsed["error"]["message"].as_str().unwrap().contains(message),
            "{parsed}"
        );
    }
    let mut non_utf8 = form(Some(&wav), &[]);
    let tail = format!("--{BOUNDARY}--\r\n");
    non_utf8.truncate(non_utf8.len() - tail.len());
    non_utf8.extend_from_slice(
        format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n").as_bytes(),
    );
    non_utf8.extend_from_slice(b"\xff\xfe\r\n");
    non_utf8.extend_from_slice(tail.as_bytes());
    let (status, _, body) = send(&h.app, upload(non_utf8)).await;
    assert_eq!(
        (
            status.as_u16(),
            json_of(&body)["error"]["message"].as_str().unwrap()
        ),
        (400, "field 'prompt' is not UTF-8")
    );
    let nested = format!(
        "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"\r\nContent-Type: multipart/mixed; boundary=x\r\n\r\nx\r\n--{BOUNDARY}--\r\n"
    );
    let (status, _, body) = send(&h.app, upload(nested.into_bytes())).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (400, "bad_request"));
    assert!(h.recognizer.calls().is_empty());
}

#[tokio::test]
async fn non_multipart_is_400() {
    let h = harness(Options::default()).await;
    let request = Request::post("/v1/audio/transcriptions")
        .header("authorization", format!("Bearer {KEY}"))
        .header("content-type", "application/json")
        .body(Body::from("{\"file\": \"x\"}"))
        .unwrap();
    let (status, _, body) = send(&h.app, request).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (400, "bad_request"));
    let no_boundary = Request::post("/v1/audio/transcriptions")
        .header("authorization", format!("Bearer {KEY}"))
        .header("content-type", "multipart/form-data")
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&h.app, no_boundary).await.0, 400);
}

#[tokio::test]
async fn too_long_audio_and_too_large_uploads_are_413() {
    let options = Options {
        limits: HttpLimits {
            max_upload_bytes: 20_000,
            multipart_overhead_bytes: 1_000,
            ..Options::default().limits
        },
        engine: EngineLimits {
            max_audio_seconds: 0.5,
            ..Options::default().engine
        },
        ..Options::default()
    };
    let h = harness(options).await;
    let (status, _, body) = send(&h.app, upload(form(Some(&wav_tone(0.6, 16_000, 1)), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    assert_eq!(
        json_of(&body)["error"]["message"],
        "audio is longer than 0.01 minutes"
    );
    // Declared too large.
    let (status, _, body) = send(&h.app, upload(form(Some(&wav_tone(1.0, 16_000, 1)), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    // Chunked, no Content-Length.
    let chunks =
        futures_util::stream::iter((0..10).map(|_| Ok::<_, std::io::Error>(Bytes::from(vec![b'z'; 8192]))));
    let request = Request::post("/v1/audio/transcriptions")
        .header("authorization", format!("Bearer {KEY}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={BOUNDARY}"),
        )
        .body(Body::from_stream(chunks))
        .unwrap();
    let (status, _, body) = send(&h.app, request).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    // File part over the limit even though the whole body fits the allowance.
    let options = Options {
        limits: HttpLimits {
            max_upload_bytes: 10_000,
            multipart_overhead_bytes: 1 << 20,
            ..Options::default().limits
        },
        ..Options::default()
    };
    let h = harness(options).await;
    let (status, _, body) = send(&h.app, upload(form(Some(&wav_tone(1.0, 16_000, 1)), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    assert!(h.recognizer.calls().is_empty());
}

#[tokio::test]
async fn not_ready_is_503_with_retry_after() {
    let h = harness(Options {
        ready: false,
        ..Options::default()
    })
    .await;
    let (status, headers, body) = send(&h.app, upload(form(Some(&wav_tone(0.2, 16_000, 1)), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (503, "not_ready"));
    assert_eq!(headers["retry-after"], "5");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn too_many_transcriptions_in_flight_is_429() {
    let (fake, gate) = FakeRecognizer::new("x").gated();
    let options = Options {
        recognizer: fake,
        engine: EngineLimits {
            max_concurrent: 1,
            max_waiting: 1,
            ..Options::default().engine
        },
        ..Options::default()
    };
    let h = harness(options).await;
    let wav = wav_tone(0.2, 16_000, 1);
    let first = tokio::spawn(send_owned(h.app.clone(), upload(form(Some(&wav), &[]))));
    let second = tokio::spawn(send_owned(h.app.clone(), upload(form(Some(&wav), &[]))));
    for _ in 0..100 {
        if h.engine.in_flight() == 2 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let (status, _, body) = send(&h.app, upload(form(Some(&wav), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (429, "busy"));
    open_gate(&gate);
    assert_eq!(first.await.unwrap(), 200);
    assert_eq!(second.await.unwrap(), 200);
}

async fn send_owned(app: Router, request: Request<Body>) -> u16 {
    app.oneshot(request).await.unwrap().status().as_u16()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn recognition_timeout_is_504() {
    let options = Options {
        recognizer: FakeRecognizer::new("x").with_delay(Duration::from_millis(500)),
        engine: EngineLimits {
            recognition_timeout: Duration::from_millis(100),
            ..Options::default().engine
        },
        ..Options::default()
    };
    let h = harness(options).await;
    let (status, _, body) = send(&h.app, upload(form(Some(&wav_tone(0.2, 16_000, 1)), &[]))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (504, "backend_timeout"));
}

// Chat

#[tokio::test]
async fn chat_model_selects_upstream_and_reply_is_unchanged() {
    let h = harness(Options::default()).await;
    for (model, fake, other, sent_model) in [
        ("cleanup", &h.cleanup, &h.commands, "cleanup"),
        ("assistant", &h.commands, &h.cleanup, "qwen3.5-4b-q4_k_m"),
        ("gemma-4-12b", &h.commands, &h.cleanup, "gemma-4-12b"),
    ] {
        let before = (fake.seen().len(), other.seen().len());
        let mut request = chat_request(chat_json(model));
        request
            .headers_mut()
            .insert("x-request-id", "r-1".parse().unwrap());
        let (status, headers, body) = send(&h.app, request).await;
        assert_eq!(status, 200, "{model}");
        let name = fake.state.lock().unwrap().name.clone();
        assert_eq!(
            body,
            json!({"backend": name, "choices": [{"message": {"content": "Six pm."}}]})
                .to_string()
                .as_bytes()
        );
        let timing = headers["server-timing"].to_str().unwrap();
        assert!(
            timing.starts_with("llm;dur=") && timing.contains("gate;dur="),
            "{timing}"
        );
        assert_eq!((fake.seen().len(), other.seen().len()), (before.0 + 1, before.1));
        let (sent_headers, sent) = fake.seen().last().cloned().unwrap();
        assert_eq!(json_of(&sent)["model"], sent_model);
        assert_eq!(sent_headers["x-request-id"], "r-1");
    }
    assert_eq!(
        h.cleanup.seen()[0].1,
        chat_json("cleanup").as_bytes(),
        "forwarded byte for byte"
    );
}

#[tokio::test]
async fn chat_bad_requests_are_400_and_not_forwarded() {
    let h = harness(Options::default()).await;
    for body in [
        "not json".to_owned(),
        "[1, 2]".to_owned(),
        chat_json("gpt-4"),
        json!({"messages": [{"role": "user", "content": "x"}]}).to_string(),
        json!({"model": "cleanup", "messages": []}).to_string(),
        json!({"model": "cleanup"}).to_string(),
        json!({"model": "cleanup", "stream": 1, "messages": [{"role": "user", "content": "x"}]}).to_string(),
    ] {
        let (status, _, reply) = send(&h.app, chat_request(body.clone())).await;
        assert_eq!(
            (status.as_u16(), code(&reply).as_str()),
            (400, "bad_request"),
            "{body}"
        );
    }
    assert!(h.cleanup.seen().is_empty());
}

#[tokio::test]
async fn chat_body_over_limit_is_413() {
    let h = harness(Options::default()).await;
    let big = json!({"model": "cleanup", "messages": [{"role": "user", "content": "x".repeat(1 << 20)}]})
        .to_string();
    let (status, _, body) = send(&h.app, chat_request(big)).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (413, "too_large"));
    assert_eq!(
        json_of(&body)["error"]["message"],
        "request body is larger than 1 MB"
    );
}

#[tokio::test]
async fn llama_errors_map_to_502_503_504() {
    let h = harness(Options::default()).await;
    h.cleanup.set(|s| {
        s.chat = ChatBehaviour::Status(
            400,
            r#"{"error":{"code":400,"message":"context too long"}}"#.into(),
        );
    });
    let (status, _, body) = send(&h.app, chat_request(chat_json("cleanup"))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (502, "backend_error"));
    h.cleanup.set(|s| {
        s.chat = ChatBehaviour::Status(503, r#"{"error":{"code":503,"message":"Loading model"}}"#.into());
    });
    let (status, headers, body) = send(&h.app, chat_request(chat_json("cleanup"))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (503, "not_ready"));
    assert_eq!(headers["retry-after"], "5");
    h.commands.set(|s| s.chat_delay = Duration::from_secs(2));
    let (status, _, body) = send(&h.app, chat_request(chat_json("assistant"))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (504, "backend_timeout"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn too_many_chats_in_flight_is_429_per_upstream() {
    let h = harness(Options {
        upstream_in_flight: 1,
        ..Options::default()
    })
    .await;
    h.cleanup.set(|s| s.chat_delay = Duration::from_millis(400));
    let first = tokio::spawn(send_owned(h.app.clone(), chat_request(chat_json("cleanup"))));
    for _ in 0..100 {
        if !h.cleanup.seen().is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let (status, _, body) = send(&h.app, chat_request(chat_json("cleanup"))).await;
    assert_eq!((status.as_u16(), code(&body).as_str()), (429, "busy"));
    assert_eq!(send(&h.app, chat_request(chat_json("assistant"))).await.0, 200);
    assert_eq!(first.await.unwrap(), 200);
    assert_eq!(send(&h.app, chat_request(chat_json("cleanup"))).await.0, 200);
}

#[tokio::test]
async fn streaming_chat_passes_through() {
    let h = harness(Options::default()).await;
    let chunks = vec![
        "data: {\"choices\":[{\"delta\":{\"content\":\"Six\"}}]}\n\n".to_owned(),
        "data: [DONE]\n\n".to_owned(),
    ];
    h.cleanup
        .set(|s| s.chat = ChatBehaviour::Stream(chunks.clone(), Duration::from_millis(10)));
    let body = json!({"model": "cleanup", "stream": true, "messages": [{"role": "user", "content": "x"}]})
        .to_string();
    let (status, headers, reply) = send(&h.app, chat_request(body)).await;
    assert_eq!(status, 200);
    assert!(
        headers["content-type"]
            .to_str()
            .unwrap()
            .starts_with("text/event-stream")
    );
    assert_eq!(reply, chunks.concat().as_bytes());
}

#[tokio::test]
async fn models_lists_speech_and_upstream_ids_without_aliases() {
    let h = harness(Options::default()).await;
    let (status, _, body) = send(&h.app, get("/v1/models")).await;
    assert_eq!(status, 200);
    let body = json_of(&body);
    assert_eq!(body["object"], "list");
    let ids: Vec<_> = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["id"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(
        ids,
        ["model-stt", "model-cleanup", "qwen3.5-4b-q4_k_m", "gemma-4-12b"]
    );
    let roles: Vec<_> = body["data"]
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["telltype"]["roles"].clone())
        .collect();
    assert_eq!(
        roles,
        [
            serde_json::json!(["speech"]),
            serde_json::json!(["cleanup"]),
            serde_json::json!(["commands"]),
            serde_json::json!(["commands"])
        ],
        "every entry says which role it serves"
    );
}
