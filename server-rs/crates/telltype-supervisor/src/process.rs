//! One supervised process: start, health, restart with back-off, stop.

use std::os::unix::process::ExitStatusExt;
use std::path::PathBuf;
use std::process::Stdio;
use std::time::{Duration, Instant};

use nix::sys::signal::{Signal, killpg};
use nix::unistd::Pid;
use tokio::process::{Child, Command};
use tokio::sync::watch;
use tokio::task::JoinHandle;

/// What to run and how to watch it.
#[derive(Debug, Clone)]
pub struct ProcessSpec {
    /// For logs, e.g. `cleanup`.
    pub name: String,
    pub program: PathBuf,
    pub args: Vec<String>,
    /// Answers 200 when the process can serve, e.g. `http://127.0.0.1:8172/health`.
    pub health_url: String,
    pub restart_min: Duration,
    pub restart_max: Duration,
    /// Time between SIGTERM and SIGKILL when stopping.
    pub stop_timeout: Duration,
    /// Health checks once the process is ready (before that, every 500 ms).
    pub health_interval: Duration,
    /// Consecutive failed checks after being ready that count as hung (the process is restarted).
    pub unhealthy_limit: u32,
    /// A run at least this long resets the back-off.
    pub stable_after: Duration,
}

impl ProcessSpec {
    /// A spec with the default watch settings (checks every 10 s, 3 misses, stable after 60 s).
    #[must_use]
    pub fn new(name: &str, program: PathBuf, args: Vec<String>, health_url: String) -> Self {
        Self {
            name: name.to_owned(),
            program,
            args,
            health_url,
            restart_min: Duration::from_secs(1),
            restart_max: Duration::from_secs(60),
            stop_timeout: Duration::from_secs(20),
            health_interval: Duration::from_secs(10),
            unhealthy_limit: 3,
            stable_after: Duration::from_secs(60),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SupervisorStatus {
    pub pid: Option<u32>,
    /// How many times the process was started (or a start was attempted).
    pub starts: u32,
    pub healthy: bool,
}

pub struct Supervisor {
    status: watch::Receiver<SupervisorStatus>,
    stop: watch::Sender<bool>,
    task: JoinHandle<()>,
}

enum Outcome {
    Exited,
    Hung,
    Stopped,
}

impl Supervisor {
    /// Start the process and keep it running until [`Supervisor::stop`].
    #[must_use]
    pub fn spawn(spec: ProcessSpec) -> Self {
        let (status_tx, status) = watch::channel(SupervisorStatus::default());
        let (stop, stop_rx) = watch::channel(false);
        let task = tokio::spawn(supervise(spec, status_tx, stop_rx));
        Self { status, stop, task }
    }

    #[must_use]
    pub fn status(&self) -> SupervisorStatus {
        self.status.borrow().clone()
    }

    /// Wait until the process is healthy or `timeout` passes; returns whether it is healthy.
    pub async fn wait_healthy(&mut self, timeout: Duration) -> bool {
        tokio::time::timeout(timeout, self.status.wait_for(|s| s.healthy))
            .await
            .is_ok_and(|r| r.is_ok())
    }

    /// Stop the process (SIGTERM to its process group, SIGKILL after the stop timeout) and wait.
    pub async fn stop(self) {
        let _ = self.stop.send(true);
        let _ = self.task.await;
    }
}

async fn supervise(
    spec: ProcessSpec,
    status: watch::Sender<SupervisorStatus>,
    mut stop: watch::Receiver<bool>,
) {
    let client = reqwest::Client::new();
    let mut failures: u32 = 0;
    loop {
        status.send_modify(|s| s.starts += 1);
        let started = Instant::now();
        let spawned = Command::new(&spec.program)
            .args(&spec.args)
            .stdin(Stdio::null())
            .kill_on_drop(true)
            .process_group(0)
            .spawn();
        match spawned {
            Err(e) => {
                tracing::error!(process = %spec.name, error = %e, "cannot start {}", spec.program.display());
            }
            Ok(child) => {
                tracing::info!(process = %spec.name, pid = child.id(), "started");
                match watch_child(&spec, &client, child, &status, &mut stop).await {
                    Outcome::Stopped => return,
                    Outcome::Exited | Outcome::Hung => {}
                }
                status.send_modify(|s| {
                    s.pid = None;
                    s.healthy = false;
                });
                if started.elapsed() >= spec.stable_after {
                    failures = 0;
                }
            }
        }
        failures = failures.saturating_add(1);
        let delay = backoff(spec.restart_min, spec.restart_max, failures);
        tracing::warn!(process = %spec.name, delay_ms = delay.as_millis(), "restarting after a delay");
        tokio::select! {
            () = tokio::time::sleep(delay) => {}
            () = stopped(&mut stop) => return,
        }
    }
}

async fn watch_child(
    spec: &ProcessSpec,
    client: &reqwest::Client,
    mut child: Child,
    status: &watch::Sender<SupervisorStatus>,
    stop: &mut watch::Receiver<bool>,
) -> Outcome {
    let pid = child.id();
    status.send_modify(|s| s.pid = pid);
    let started = Instant::now();
    let mut healthy = false;
    let mut misses = 0;
    loop {
        let interval = if healthy {
            spec.health_interval
        } else {
            Duration::from_millis(500)
        };
        tokio::select! {
            exit = child.wait() => {
                let detail = exit.map_or_else(|e| e.to_string(), |s| match (s.code(), s.signal()) {
                    (Some(code), _) => format!("exit code {code}"),
                    (None, Some(signal)) => format!("signal {signal}"),
                    _ => s.to_string(),
                });
                tracing::warn!(process = %spec.name, %detail, "exited");
                return Outcome::Exited;
            }
            () = stopped(stop) => {
                terminate(spec, &mut child).await;
                return Outcome::Stopped;
            }
            () = tokio::time::sleep(interval) => {
                let ok = check(client, &spec.health_url).await;
                if ok {
                    misses = 0;
                    if !healthy {
                        healthy = true;
                        status.send_modify(|s| s.healthy = true);
                        tracing::info!(process = %spec.name, ms = started.elapsed().as_millis(), "ready");
                    }
                } else if healthy {
                    misses += 1;
                    if misses >= spec.unhealthy_limit {
                        tracing::warn!(process = %spec.name, misses, "not answering its health check; restarting");
                        terminate(spec, &mut child).await;
                        return Outcome::Hung;
                    }
                }
            }
        }
    }
}

/// Resolves once a stop was requested (or the supervisor handle is gone).
async fn stopped(stop: &mut watch::Receiver<bool>) {
    let _ = stop.wait_for(|stopped| *stopped).await;
}

async fn check(client: &reqwest::Client, url: &str) -> bool {
    let request = client.get(url).timeout(Duration::from_secs(5)).send();
    request.await.is_ok_and(|r| r.status().as_u16() == 200)
}

/// SIGTERM to the process group (llama-server's router starts model processes of its own), then
/// SIGKILL after the stop timeout.
async fn terminate(spec: &ProcessSpec, child: &mut Child) {
    let Some(pid) = child.id().and_then(|p| i32::try_from(p).ok()) else {
        return;
    };
    let group = Pid::from_raw(pid);
    let _ = killpg(group, Signal::SIGTERM);
    if tokio::time::timeout(spec.stop_timeout, child.wait())
        .await
        .is_ok()
    {
        tracing::info!(process = %spec.name, "stopped");
        return;
    }
    tracing::warn!(process = %spec.name, "did not stop within the timeout; killing it");
    let _ = killpg(group, Signal::SIGKILL);
    let _ = child.wait().await;
}

/// `min * 2^(failures-1)`, at most `max`.
fn backoff(min: Duration, max: Duration, failures: u32) -> Duration {
    let factor = 2u32.saturating_pow(failures.saturating_sub(1).min(16));
    min.saturating_mul(factor).min(max)
}

#[cfg(test)]
mod tests;
