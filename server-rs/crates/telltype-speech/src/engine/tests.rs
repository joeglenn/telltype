use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use super::{EngineLimits, SpeechEngine};
use crate::error::{DecodeError, SpeechError};
use crate::testing::{FakeRecognizer, open_gate, wav_tone};

fn limits() -> EngineLimits {
    EngineLimits {
        max_concurrent: 2,
        max_waiting: 1,
        max_audio_seconds: 360.0,
        recognition_timeout: Duration::from_secs(5),
        max_hotwords: None,
    }
}

fn ready_engine(limits: EngineLimits, fake: FakeRecognizer) -> (Arc<SpeechEngine>, Arc<FakeRecognizer>) {
    let engine = Arc::new(SpeechEngine::new(limits));
    let fake = Arc::new(fake);
    engine.set_recognizer(fake.clone());
    (engine, fake)
}

#[tokio::test]
async fn transcribes_and_reports_duration_and_timings() {
    let (engine, fake) = ready_engine(limits(), FakeRecognizer::new("hello world"));
    let admission = engine.admit().unwrap();
    let transcript = engine
        .transcribe(&admission, wav_tone(1.5, 16_000, 1), Some("Hyrule"))
        .await
        .unwrap();
    assert_eq!(transcript.text, "hello world");
    assert!((transcript.duration_s - 1.5).abs() < 1e-9);
    assert!(transcript.timings.decode_ms >= 0.0 && transcript.timings.stt_ms >= 0.0);
    // Hot words are off: the prompt is accepted and ignored.
    assert_eq!(fake.calls(), [(24_000, vec![])]);
    drop(admission);
    assert_eq!(engine.in_flight(), 0);
}

#[tokio::test]
async fn prompt_terms_become_hotwords_when_enabled() {
    let (engine, fake) = ready_engine(
        EngineLimits {
            max_hotwords: Some(2),
            ..limits()
        },
        FakeRecognizer::new("x"),
    );
    let admission = engine.admit().unwrap();
    engine
        .transcribe(
            &admission,
            wav_tone(0.5, 16_000, 1),
            Some("Kubernetes, kubernetes, Hyrule:3, Igor"),
        )
        .await
        .unwrap();
    assert_eq!(fake.calls()[0].1, ["Kubernetes", "Hyrule 3"]);
}

#[tokio::test]
async fn not_ready_until_a_recognizer_is_set() {
    let engine = SpeechEngine::new(limits());
    assert!(!engine.ready());
    assert_eq!(engine.admit().unwrap_err(), SpeechError::NotReady);
    assert_eq!(engine.in_flight(), 0, "a refused admission releases its place");
    engine.set_recognizer(Arc::new(FakeRecognizer::new("a")));
    engine.set_recognizer(Arc::new(FakeRecognizer::new("b")));
    let admission = engine.admit().unwrap();
    let text = engine
        .transcribe(&admission, wav_tone(0.2, 16_000, 1), None)
        .await
        .unwrap()
        .text;
    assert_eq!(text, "a", "the first recogniser stays");
}

#[tokio::test]
async fn decode_errors_pass_through_typed() {
    let (engine, fake) = ready_engine(limits(), FakeRecognizer::new("x"));
    let admission = engine.admit().unwrap();
    let err = engine
        .transcribe(&admission, b"garbage".to_vec(), None)
        .await
        .unwrap_err();
    assert!(
        matches!(err, SpeechError::Decode(DecodeError::Unsupported(_))),
        "{err:?}"
    );
    assert!(fake.calls().is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn admission_limit_is_concurrent_plus_waiting() {
    let (fake, gate) = FakeRecognizer::new("x").gated();
    let (engine, fake) = ready_engine(limits(), fake);
    let mut tasks = Vec::new();
    for _ in 0..3 {
        let admission = engine.admit().unwrap();
        let engine = Arc::clone(&engine);
        tasks.push(tokio::spawn(async move {
            engine
                .transcribe(&admission, wav_tone(0.2, 16_000, 1), None)
                .await
        }));
    }
    assert_eq!(
        engine.admit().unwrap_err(),
        SpeechError::Busy {
            in_flight: 3,
            limit: 3
        }
    );
    // Only max_concurrent recognitions run at once.
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(fake.running.load(Ordering::Acquire), 2);
    open_gate(&gate);
    for task in tasks {
        assert!(task.await.unwrap().is_ok());
    }
    assert_eq!(engine.in_flight(), 0);
    assert!(engine.admit().is_ok());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn recognition_timeout_is_typed_and_slot_stays_held() {
    let (fake, gate) = FakeRecognizer::new("x").gated();
    let limits = EngineLimits {
        max_concurrent: 1,
        recognition_timeout: Duration::from_millis(100),
        ..limits()
    };
    let (engine, fake) = ready_engine(limits, fake);
    let admission = engine.admit().unwrap();
    let err = engine
        .transcribe(&admission, wav_tone(0.2, 16_000, 1), None)
        .await
        .unwrap_err();
    assert_eq!(err, SpeechError::Timeout { seconds: 0 });
    assert_eq!(
        engine.slots.available_permits(),
        0,
        "the timed-out recognition still holds its slot"
    );
    assert_eq!(fake.running.load(Ordering::Acquire), 1);
    open_gate(&gate);
    for _ in 0..50 {
        if engine.slots.available_permits() == 1 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(engine.slots.available_permits(), 1);
}
