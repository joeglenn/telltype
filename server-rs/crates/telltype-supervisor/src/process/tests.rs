use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use axum::Router;
use axum::http::StatusCode;
use axum::routing::get;
use nix::sys::signal::kill;
use nix::unistd::Pid;

use super::{ProcessSpec, Supervisor, backoff};

/// A health endpoint whose answer the test controls.
async fn health_server(healthy: Arc<AtomicBool>) -> String {
    let app = Router::new().route(
        "/health",
        get(move || {
            let healthy = healthy.clone();
            async move {
                if healthy.load(Ordering::Acquire) {
                    StatusCode::OK
                } else {
                    StatusCode::SERVICE_UNAVAILABLE
                }
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await });
    format!("http://{addr}/health")
}

fn sh(script: &str, health_url: &str) -> ProcessSpec {
    let mut spec = ProcessSpec::new(
        "test",
        PathBuf::from("/bin/sh"),
        vec!["-c".into(), script.into()],
        health_url.into(),
    );
    spec.restart_min = Duration::from_millis(50);
    spec.restart_max = Duration::from_millis(100);
    spec.stop_timeout = Duration::from_millis(500);
    spec.health_interval = Duration::from_millis(100);
    spec
}

fn alive(pid: u32) -> bool {
    kill(Pid::from_raw(i32::try_from(pid).unwrap()), None).is_ok()
}

async fn wait_until(mut condition: impl FnMut() -> bool) -> bool {
    for _ in 0..100 {
        if condition() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    false
}

#[test]
fn backoff_doubles_up_to_the_maximum() {
    let min = Duration::from_secs(1);
    let max = Duration::from_secs(60);
    let delays: Vec<u64> = (1..=8).map(|n| backoff(min, max, n).as_secs()).collect();
    assert_eq!(delays, [1, 2, 4, 8, 16, 32, 60, 60]);
    assert_eq!(backoff(min, max, u32::MAX), max);
}

#[tokio::test]
async fn starts_becomes_healthy_and_stops_with_sigterm() {
    let url = health_server(Arc::new(AtomicBool::new(true))).await;
    let mut supervisor = Supervisor::spawn(sh("exec sleep 30", &url));
    assert!(supervisor.wait_healthy(Duration::from_secs(5)).await);
    let status = supervisor.status();
    assert_eq!(status.starts, 1);
    let pid = status.pid.unwrap();
    assert!(alive(pid));
    supervisor.stop().await;
    assert!(!alive(pid), "the process is gone after stop");
}

#[tokio::test]
async fn a_process_that_ignores_sigterm_is_killed_after_the_timeout() {
    let url = health_server(Arc::new(AtomicBool::new(true))).await;
    let supervisor = Supervisor::spawn(sh("trap '' TERM; sleep 30 & wait; sleep 30", &url));
    assert!(wait_until(|| supervisor.status().pid.is_some()).await);
    let pid = supervisor.status().pid.unwrap();
    let started = std::time::Instant::now();
    supervisor.stop().await;
    assert!(started.elapsed() >= Duration::from_millis(400));
    assert!(!alive(pid));
}

#[tokio::test]
async fn an_exiting_process_is_restarted_with_backoff() {
    let url = health_server(Arc::new(AtomicBool::new(false))).await;
    let supervisor = Supervisor::spawn(sh("exit 3", &url));
    assert!(
        wait_until(|| supervisor.status().starts >= 3).await,
        "{:?}",
        supervisor.status()
    );
    assert!(!supervisor.status().healthy);
    supervisor.stop().await;
}

#[tokio::test]
async fn a_missing_binary_is_retried_without_panicking() {
    let mut spec = sh("", "http://127.0.0.1:9/health");
    spec.program = PathBuf::from("/nonexistent/llama-server");
    let supervisor = Supervisor::spawn(spec);
    assert!(wait_until(|| supervisor.status().starts >= 2).await);
    supervisor.stop().await;
}

#[tokio::test]
async fn a_hung_process_is_restarted() {
    let healthy = Arc::new(AtomicBool::new(true));
    let url = health_server(healthy.clone()).await;
    let mut supervisor = Supervisor::spawn(sh("exec sleep 30", &url));
    assert!(supervisor.wait_healthy(Duration::from_secs(5)).await);
    let first = supervisor.status().pid.unwrap();
    healthy.store(false, Ordering::Release);
    assert!(
        wait_until(|| supervisor.status().starts >= 2).await,
        "{:?}",
        supervisor.status()
    );
    assert!(wait_until(|| !alive(first)).await);
    healthy.store(true, Ordering::Release);
    assert!(supervisor.wait_healthy(Duration::from_secs(5)).await);
    supervisor.stop().await;
}
