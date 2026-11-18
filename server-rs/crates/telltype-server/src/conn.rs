//! Connection limits below HTTP: a global cap and a per-address cap on open connections, and an
//! idle timeout (no byte read or written for `idle_timeout`). A connection over a cap is closed
//! right after `accept`, before TLS or HTTP cost anything.

use std::collections::HashMap;
use std::future::Future;
use std::io;
use std::net::{IpAddr, Ipv6Addr};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

use axum_server::accept::Accept;
use telltype_config::{IpNet, canonical};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::time::{Instant, Sleep};

/// The caps and the idle timeout.
#[derive(Debug, Clone)]
pub struct ConnLimits {
    pub max_total: usize,
    pub max_per_ip: usize,
    pub idle: Duration,
    /// Addresses exempt from the per-address cap (trusted reverse proxies).
    pub exempt: Vec<IpNet>,
}

#[derive(Debug, Default)]
struct Counts {
    total: usize,
    per_ip: HashMap<IpAddr, usize>,
}

#[derive(Debug)]
struct Shared {
    limits: ConnLimits,
    counts: Mutex<Counts>,
    refused: AtomicU64,
}

/// Counts open connections; wraps each accepted stream in an [`Limited`] stream.
#[derive(Debug, Clone)]
pub struct LimitAcceptor {
    shared: Arc<Shared>,
}

/// Where the per-address cap counts: the address, IPv6 by its /64.
fn slot(ip: IpAddr) -> IpAddr {
    match canonical(ip) {
        IpAddr::V6(v6) => IpAddr::V6(Ipv6Addr::from(u128::from(v6) & !u128::from(u64::MAX))),
        v4 @ IpAddr::V4(_) => v4,
    }
}

impl LimitAcceptor {
    #[must_use]
    pub fn new(limits: ConnLimits) -> Self {
        Self {
            shared: Arc::new(Shared {
                limits,
                counts: Mutex::new(Counts::default()),
                refused: AtomicU64::new(0),
            }),
        }
    }

    /// Connections refused by a cap since start.
    #[must_use]
    pub fn refused(&self) -> u64 {
        self.shared.refused.load(Ordering::Relaxed)
    }

    /// Open connections (all, and from `ip`'s slot).
    #[must_use]
    pub fn open(&self, ip: IpAddr) -> (usize, usize) {
        self.shared.counts.lock().map_or((0, 0), |c| {
            (c.total, c.per_ip.get(&slot(ip)).copied().unwrap_or(0))
        })
    }

    fn admit(&self, ip: Option<IpAddr>) -> Result<Ticket, io::Error> {
        let shared = &self.shared;
        let exempt = ip.is_none_or(|ip| shared.limits.exempt.iter().any(|n| n.contains(ip)));
        let key = ip.map(slot).filter(|_| !exempt);
        let mut counts = shared
            .counts
            .lock()
            .map_err(|_| io::Error::other("connection counter poisoned"))?;
        let per_ip = key.map_or(0, |k| counts.per_ip.get(&k).copied().unwrap_or(0));
        if counts.total >= shared.limits.max_total || per_ip >= shared.limits.max_per_ip {
            drop(counts);
            let refused = shared.refused.fetch_add(1, Ordering::Relaxed) + 1;
            // One log line for the first refusal and then every 1000th, not one per connection.
            if refused == 1 || refused.is_multiple_of(1000) {
                tracing::warn!(
                    event = "connections_refused",
                    refused,
                    per_ip = per_ip >= shared.limits.max_per_ip
                );
            }
            return Err(io::Error::other("connection limit reached"));
        }
        counts.total += 1;
        if let Some(k) = key {
            *counts.per_ip.entry(k).or_insert(0) += 1;
        }
        Ok(Ticket {
            shared: Arc::clone(shared),
            key,
        })
    }
}

/// Gives the connection's place back when the stream is dropped.
#[derive(Debug)]
struct Ticket {
    shared: Arc<Shared>,
    key: Option<IpAddr>,
}

impl Drop for Ticket {
    fn drop(&mut self) {
        if let Ok(mut counts) = self.shared.counts.lock() {
            counts.total = counts.total.saturating_sub(1);
            if let Some(key) = self.key
                && let Some(n) = counts.per_ip.get_mut(&key)
            {
                *n = n.saturating_sub(1);
                if *n == 0 {
                    counts.per_ip.remove(&key);
                }
            }
        }
    }
}

impl<S: Send + 'static> Accept<TcpStream, S> for LimitAcceptor {
    type Stream = Limited<TcpStream>;
    type Service = S;
    type Future = Pin<Box<dyn Future<Output = io::Result<(Self::Stream, S)>> + Send>>;

    fn accept(&self, stream: TcpStream, service: S) -> Self::Future {
        let ip = stream.peer_addr().ok().map(|a| a.ip());
        let admitted = self.admit(ip);
        let idle = self.shared.limits.idle;
        Box::pin(async move {
            let ticket = admitted?;
            let _ = stream.set_nodelay(true);
            Ok((Limited::new(stream, idle, ticket), service))
        })
    }
}

/// A stream that fails with `TimedOut` after `idle` without a byte read or written.
#[derive(Debug)]
pub struct Limited<T> {
    inner: T,
    idle: Duration,
    timer: Pin<Box<Sleep>>,
    _ticket: Ticket,
}

impl<T> Limited<T> {
    fn new(inner: T, idle: Duration, ticket: Ticket) -> Self {
        Self {
            inner,
            idle,
            timer: Box::pin(tokio::time::sleep(idle)),
            _ticket: ticket,
        }
    }

    fn active(&mut self) {
        if let Some(deadline) = Instant::now().checked_add(self.idle) {
            self.timer.as_mut().reset(deadline);
        }
    }

    fn expired(&mut self, cx: &mut Context<'_>) -> bool {
        self.timer.as_mut().poll(cx).is_ready()
    }
}

fn timed_out() -> io::Error {
    io::Error::new(io::ErrorKind::TimedOut, "connection idle")
}

impl<T: AsyncRead + Unpin> AsyncRead for Limited<T> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let before = buf.filled().len();
        match Pin::new(&mut self.inner).poll_read(cx, buf) {
            Poll::Ready(result) => {
                if buf.filled().len() > before {
                    self.active();
                }
                Poll::Ready(result)
            }
            Poll::Pending if self.expired(cx) => Poll::Ready(Err(timed_out())),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl<T: AsyncWrite + Unpin> AsyncWrite for Limited<T> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, data: &[u8]) -> Poll<io::Result<usize>> {
        match Pin::new(&mut self.inner).poll_write(cx, data) {
            Poll::Ready(Ok(n)) => {
                if n > 0 {
                    self.active();
                }
                Poll::Ready(Ok(n))
            }
            Poll::Pending if self.expired(cx) => Poll::Ready(Err(timed_out())),
            other => other,
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match Pin::new(&mut self.inner).poll_flush(cx) {
            Poll::Pending if self.expired(cx) => Poll::Ready(Err(timed_out())),
            other => other,
        }
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }

    fn poll_write_vectored(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        match Pin::new(&mut self.inner).poll_write_vectored(cx, bufs) {
            Poll::Ready(Ok(n)) => {
                if n > 0 {
                    self.active();
                }
                Poll::Ready(Ok(n))
            }
            Poll::Pending if self.expired(cx) => Poll::Ready(Err(timed_out())),
            other => other,
        }
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }
}

#[cfg(test)]
mod tests {
    use std::net::IpAddr;
    use std::time::Duration;

    use super::{ConnLimits, LimitAcceptor};

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    #[test]
    fn caps_count_and_free_places() {
        let acceptor = LimitAcceptor::new(ConnLimits {
            max_total: 3,
            max_per_ip: 2,
            idle: Duration::from_secs(1),
            exempt: vec!["10.0.0.1".parse().unwrap()],
        });
        let a1 = acceptor.admit(Some(ip("192.0.2.1"))).unwrap();
        let _a2 = acceptor.admit(Some(ip("::ffff:192.0.2.1"))).unwrap();
        assert!(acceptor.admit(Some(ip("192.0.2.1"))).is_err(), "per address");
        let _b = acceptor.admit(Some(ip("10.0.0.1"))).unwrap();
        assert!(acceptor.admit(Some(ip("192.0.2.9"))).is_err(), "global");
        assert_eq!(acceptor.open(ip("192.0.2.1")), (3, 2));
        drop(a1);
        assert_eq!(acceptor.open(ip("192.0.2.1")), (2, 1));
        let _c = acceptor.admit(Some(ip("2001:db8::1"))).unwrap();
        assert_eq!(acceptor.open(ip("2001:db8::2")), (3, 1), "IPv6 per /64");
        assert_eq!(acceptor.refused(), 2);
    }
}
