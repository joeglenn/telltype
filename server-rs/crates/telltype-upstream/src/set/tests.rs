use std::collections::BTreeMap;
use std::time::Duration;

use bytes::Bytes;
use futures_util::StreamExt;
use serde_json::{Value, json};

use super::{UpstreamSpec, Upstreams};
use crate::chat::ReplyBody;
use crate::error::UpstreamError;
use crate::testing::{ChatBehaviour, FakeUpstream, closed_url};

fn spec(role: &str, url: &str, aliases: &[(&str, &str)], max_in_flight: usize) -> UpstreamSpec {
    UpstreamSpec {
        role: role.to_owned(),
        url: url.to_owned(),
        model: format!("model-{role}"),
        aliases: aliases
            .iter()
            .map(|(a, t)| ((*a).to_owned(), (*t).to_owned()))
            .collect::<BTreeMap<_, _>>(),
        timeout: Duration::from_millis(500),
        max_in_flight,
        health_path: "/health".to_owned(),
    }
}

async fn pair() -> (Upstreams, FakeUpstream, FakeUpstream) {
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let commands = FakeUpstream::start("commands", &["qwen3.5-4b", "gemma-12b"])
        .await
        .unwrap();
    let set = Upstreams::new(
        vec![
            spec("cleanup", &cleanup.url(), &[("cleanup", "cleanup")], 1),
            spec("commands", &commands.url(), &[("assistant", "qwen3.5-4b")], 1),
        ],
        Duration::from_millis(300),
    )
    .unwrap();
    (set, cleanup, commands)
}

fn chat_body(model: &str) -> Bytes {
    Bytes::from(
        json!({"model": model, "messages": [{"role": "user", "content": "hi"}], "temperature": 0.1})
            .to_string(),
    )
}

fn full(body: ReplyBody) -> Value {
    match body {
        ReplyBody::Full(bytes) => serde_json::from_slice(&bytes).unwrap(),
        ReplyBody::Stream(_) => panic!("expected a full body"),
    }
}

#[tokio::test]
async fn aliases_route_and_same_name_alias_forwards_bytes_unchanged() {
    let (set, cleanup, commands) = pair().await;
    let body = chat_body("cleanup");
    let reply = set.chat(body.clone(), "r-1").await.unwrap();
    assert_eq!(reply.role, "cleanup");
    assert_eq!(full(reply.body)["backend"], "cleanup");
    let (forwarded_headers, forwarded) = &cleanup.seen()[0];
    assert_eq!(forwarded, &body, "byte for byte");
    assert_eq!(forwarded_headers["x-request-id"], "r-1");
    assert!(forwarded_headers.get("authorization").is_none());
    assert!(commands.seen().is_empty());
}

#[tokio::test]
async fn alias_to_another_name_rewrites_only_the_model() {
    let (set, _cleanup, commands) = pair().await;
    let reply = set.chat(chat_body("assistant"), "r-2").await.unwrap();
    assert_eq!(reply.role, "commands");
    let rewritten: Value = serde_json::from_slice(&commands.seen()[0].1).unwrap();
    assert_eq!(
        rewritten,
        json!({"model": "qwen3.5-4b", "messages": [{"role": "user", "content": "hi"}], "temperature": 0.1})
    );
    let keys: Vec<_> = rewritten.as_object().unwrap().keys().cloned().collect();
    assert_eq!(keys, ["model", "messages", "temperature"], "field order is kept");
}

#[tokio::test]
async fn listed_model_names_route_and_unknown_names_are_400() {
    let (set, _cleanup, commands) = pair().await;
    let reply = set.chat(chat_body("gemma-12b"), "r").await.unwrap();
    assert_eq!(reply.role, "commands");
    assert_eq!(commands.seen()[0].1, chat_body("gemma-12b"));
    let err = set.chat(chat_body("gpt-4"), "r").await.err().unwrap();
    assert!(
        matches!(&err, UpstreamError::BadRequest(m) if m.contains("gpt-4")),
        "{err:?}"
    );
}

#[tokio::test]
async fn unknown_name_while_an_upstream_is_down_is_not_ready() {
    let dead = closed_url().await.unwrap();
    let set = Upstreams::new(
        vec![spec("cleanup", &dead, &[("cleanup", "cleanup")], 1)],
        Duration::from_millis(200),
    )
    .unwrap();
    assert!(matches!(
        set.chat(chat_body("x"), "r").await,
        Err(UpstreamError::NotReady(_))
    ));
    assert!(
        matches!(set.chat(chat_body("cleanup"), "r").await, Err(UpstreamError::NotReady(m)) if m.contains("not reachable"))
    );
}

#[tokio::test]
async fn bad_bodies_are_400_and_never_forwarded() {
    let (set, cleanup, _) = pair().await;
    let cases: [&[u8]; 8] = [
        b"not json",
        b"[1, 2]",
        br#"{"messages": [{"role": "user", "content": "x"}]}"#,
        br#"{"model": "", "messages": [{"role": "user", "content": "x"}]}"#,
        br#"{"model": "cleanup", "messages": []}"#,
        br#"{"model": "cleanup"}"#,
        br#"{"model": "cleanup", "stream": "yes", "messages": [{"role": "user", "content": "x"}]}"#,
        br#"{"model": 5, "messages": [{"role": "user", "content": "x"}]}"#,
    ];
    for body in cases {
        let err = set.chat(Bytes::from_static(body), "r").await.err().unwrap();
        assert!(
            matches!(err, UpstreamError::BadRequest(_)),
            "{}",
            String::from_utf8_lossy(body)
        );
    }
    assert!(cleanup.seen().is_empty());
}

#[tokio::test]
async fn upstream_errors_map_to_typed_errors() {
    let (set, cleanup, _) = pair().await;
    cleanup.set(|s| {
        s.chat = ChatBehaviour::Status(
            400,
            r#"{"error":{"code":400,"message":"context too long"}}"#.into(),
        );
    });
    assert_eq!(
        set.chat(chat_body("cleanup"), "r").await.err(),
        Some(UpstreamError::Backend("cleanup returned HTTP 400".into()))
    );
    cleanup.set(|s| {
        s.chat = ChatBehaviour::Status(503, r#"{"error":{"code":503,"message":"Loading model"}}"#.into());
    });
    assert_eq!(
        set.chat(chat_body("cleanup"), "r").await.err(),
        Some(UpstreamError::NotReady("cleanup is loading".into()))
    );
    cleanup.set(|s| {
        s.chat = ChatBehaviour::Ok;
        s.chat_delay = Duration::from_secs(2);
    });
    assert_eq!(
        set.chat(chat_body("cleanup"), "r").await.err(),
        Some(UpstreamError::Timeout {
            role: "cleanup".into(),
            seconds: 0
        })
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn in_flight_limit_is_per_upstream() {
    let (set, cleanup, _) = pair().await;
    cleanup.set(|s| {
        s.chat_delay = Duration::from_millis(300);
    });
    let set = std::sync::Arc::new(set);
    let first = tokio::spawn({
        let set = set.clone();
        async move { set.chat(chat_body("cleanup"), "a").await.map(|r| r.role) }
    });
    for _ in 0..100 {
        if !cleanup.seen().is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert!(matches!(
        set.chat(chat_body("cleanup"), "b").await,
        Err(UpstreamError::Busy { .. })
    ));
    assert_eq!(
        set.chat(chat_body("assistant"), "c").await.unwrap().role,
        "commands"
    );
    assert_eq!(first.await.unwrap().unwrap(), "cleanup");
    assert!(set.chat(chat_body("cleanup"), "d").await.is_ok());
}

#[tokio::test]
async fn streaming_passes_chunks_through_and_holds_the_slot() {
    let (set, cleanup, _) = pair().await;
    let chunks = vec!["data: {\"a\":1}\n\n".to_owned(), "data: [DONE]\n\n".to_owned()];
    cleanup.set(|s| {
        s.chat = ChatBehaviour::Stream(chunks.clone(), Duration::from_millis(20));
    });
    let body = Bytes::from(r#"{"model":"cleanup","stream":true,"messages":[{"role":"user","content":"x"}]}"#);
    let reply = set.chat(body.clone(), "s").await.unwrap();
    assert!(reply.content_type.starts_with("text/event-stream"));
    let ReplyBody::Stream(mut stream) = reply.body else {
        panic!("expected a stream")
    };
    assert!(
        matches!(set.chat(body, "t").await, Err(UpstreamError::Busy { .. })),
        "slot held while streaming"
    );
    let mut received = String::new();
    while let Some(chunk) = stream.next().await {
        received.push_str(std::str::from_utf8(&chunk.unwrap()).unwrap());
    }
    assert_eq!(received, chunks.concat());
    drop(stream);
    assert!(
        set.chat(chat_body("cleanup"), "u").await.is_ok(),
        "slot released after the stream"
    );
}

#[tokio::test]
async fn a_quiet_stream_ends_with_an_error() {
    let (set, cleanup, _) = pair().await;
    cleanup.set(|s| {
        s.chat = ChatBehaviour::Stream(
            vec!["data: 1\n\n".into(), "data: 2\n\n".into()],
            Duration::from_millis(800),
        );
    });
    let body = Bytes::from(r#"{"model":"cleanup","stream":true,"messages":[{"role":"user","content":"x"}]}"#);
    // The first chunk itself comes after 800 ms, beyond the 500 ms timeout: headers arrive, then silence.
    let reply = set.chat(body, "s").await.unwrap();
    let ReplyBody::Stream(mut stream) = reply.body else {
        panic!("expected a stream")
    };
    let first = stream.next().await.unwrap();
    assert_eq!(first.unwrap_err().kind(), std::io::ErrorKind::TimedOut);
    assert!(stream.next().await.is_none());
}

#[tokio::test]
async fn probes_and_merged_model_list() {
    let (set, cleanup, commands) = pair().await;
    commands.set(|s| {
        s.healthy = false;
    });
    let probes = set.probe_all().await;
    assert_eq!(
        probes
            .iter()
            .map(|p| (p.role.as_str(), p.ready))
            .collect::<Vec<_>>(),
        [("cleanup", true), ("commands", false)]
    );
    cleanup.set(|s| {
        s.health_delay = Duration::from_secs(1);
    });
    assert!(!set.probe_all().await[0].ready, "a slow probe is not ready");
    let list = set.models().await;
    let ids: Vec<_> = list
        .data
        .iter()
        .map(|d| d["id"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(
        ids,
        ["model-cleanup", "qwen3.5-4b", "gemma-12b"],
        "alias names are never listed; an upstream id that is an alias shows the real name"
    );
    assert_eq!(
        list.per_role[1],
        (
            "commands".to_owned(),
            Some(vec!["qwen3.5-4b".to_owned(), "gemma-12b".to_owned()])
        )
    );
    commands.set(|s| {
        s.models_status = 500;
    });
    assert_eq!(set.models().await.per_role[1].1, None);
    assert_eq!(set.roles(), ["cleanup", "commands"]);
}

#[tokio::test]
async fn real_name_upstream_lists_its_id_and_the_deprecated_alias_still_routes() {
    let cleanup = FakeUpstream::start("cleanup", &["gemma-e4b"]).await.unwrap();
    let mut real = spec("cleanup", &cleanup.url(), &[("cleanup", "gemma-e4b")], 1);
    real.model = "gemma-e4b".to_owned();
    let set = Upstreams::new(vec![real], Duration::from_millis(300)).unwrap();
    let list = set.models().await;
    assert_eq!(list.data.len(), 1);
    assert_eq!(list.data[0]["id"], "gemma-e4b");
    set.chat(chat_body("cleanup"), "r").await.unwrap();
    let forwarded: Value = serde_json::from_slice(&cleanup.seen()[0].1).unwrap();
    assert_eq!(
        forwarded["model"], "gemma-e4b",
        "the deprecated name is rewritten"
    );
    set.chat(chat_body("gemma-e4b"), "r").await.unwrap();
    assert_eq!(
        cleanup.seen()[1].1,
        chat_body("gemma-e4b"),
        "the real name is forwarded unchanged"
    );
}

#[tokio::test]
async fn real_name_of_an_upstream_still_serving_an_alias_id_is_rewritten_to_that_id() {
    let (set, cleanup, _) = pair().await;
    let list = set.models().await;
    assert_eq!(list.data[0]["id"], "model-cleanup");
    assert!(list.data[0].get("aliases").is_none());
    assert_eq!(list.per_role[0].1, Some(vec!["model-cleanup".to_owned()]));
    set.chat(chat_body("model-cleanup"), "r").await.unwrap();
    let forwarded: Value = serde_json::from_slice(&cleanup.seen()[0].1).unwrap();
    assert_eq!(forwarded["model"], "cleanup");
}

#[tokio::test]
async fn an_alias_to_the_real_name_reaches_an_upstream_still_serving_the_alias_id_unchanged() {
    let cleanup = FakeUpstream::start("cleanup", &["cleanup"]).await.unwrap();
    let set = Upstreams::new(
        vec![spec(
            "cleanup",
            &cleanup.url(),
            &[("cleanup", "model-cleanup")],
            1,
        )],
        Duration::from_millis(300),
    )
    .unwrap();
    assert_eq!(set.models().await.data[0]["id"], "model-cleanup");
    set.chat(chat_body("cleanup"), "r").await.unwrap();
    assert_eq!(cleanup.seen()[0].1, chat_body("cleanup"), "byte for byte");
    set.chat(chat_body("model-cleanup"), "r").await.unwrap();
    let forwarded: Value = serde_json::from_slice(&cleanup.seen()[1].1).unwrap();
    assert_eq!(forwarded["model"], "cleanup");
}

#[tokio::test]
async fn a_router_preset_of_the_cleanup_file_is_listed_once_and_routes_to_cleanup() {
    let (set, cleanup, commands) = pair().await;
    cleanup.set(|s| s.props_model_path = Some("/no/such/e4b.gguf".to_owned()));
    commands.set(|s| {
        s.models = vec!["qwen3.5-4b".to_owned(), "e4b_q4_0-it".to_owned()];
        s.model_files = BTreeMap::from([
            ("qwen3.5-4b".to_owned(), "/no/such/q4.gguf".to_owned()),
            ("e4b_q4_0-it".to_owned(), "/no/such/e4b.gguf".to_owned()),
        ]);
    });
    let list = set.models().await;
    let listed: Vec<_> = list
        .data
        .iter()
        .map(|d| {
            (
                d["id"].as_str().unwrap().to_owned(),
                d["telltype"]["roles"].clone(),
            )
        })
        .collect();
    assert_eq!(
        listed,
        [
            ("model-cleanup".to_owned(), json!(["cleanup", "commands"])),
            ("qwen3.5-4b".to_owned(), json!(["commands"])),
        ]
    );
    assert_eq!(
        list.per_role[1].1,
        Some(vec!["qwen3.5-4b".to_owned(), "model-cleanup".to_owned()])
    );
    set.chat(chat_body("e4b_q4_0-it"), "r").await.unwrap();
    assert!(
        commands.seen().is_empty(),
        "the duplicate name never loads the file twice"
    );
    let forwarded: Value = serde_json::from_slice(&cleanup.seen()[0].1).unwrap();
    assert_eq!(
        forwarded["model"], "cleanup",
        "renamed to what the cleanup upstream serves"
    );
}
