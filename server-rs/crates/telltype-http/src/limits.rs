//! Per-client rate limits: token buckets for all requests and for failed key checks, keyed by the
//! client's address (IPv6 by its /64), with a trusted proxy's `X-Forwarded-For` believed only when
//! the peer is one of `trusted_proxies`. The number of tracked clients is bounded.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv6Addr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::http::HeaderMap;
use telltype_config::{IpNet, canonical};

/// The limits, as configured.
#[derive(Debug, Clone)]
pub struct ClientPolicy {
    pub requests_per_minute: u32,
    pub auth_failures_per_minute: u32,
    pub max_clients: usize,
    pub trusted_proxies: Vec<IpNet>,
    /// Clients that are never limited (private networks by default).
    pub exempt: Vec<IpNet>,
}

impl Default for ClientPolicy {
    /// The configuration's defaults.
    fn default() -> Self {
        Self {
            requests_per_minute: 600,
            auth_failures_per_minute: 10,
            max_clients: 10_000,
            trusted_proxies: Vec::new(),
            exempt: telltype_config::DEFAULT_EXEMPT
                .iter()
                .filter_map(|net| net.parse().ok())
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct Bucket {
    tokens: f64,
    at: Instant,
}

/// Token buckets per client: `capacity` tokens, refilled at `capacity` per minute.
#[derive(Debug)]
struct Buckets {
    map: HashMap<IpAddr, Bucket>,
    capacity: f64,
    per_second: f64,
    max: usize,
}

impl Buckets {
    fn new(per_minute: u32, max: usize) -> Self {
        let capacity = f64::from(per_minute.max(1));
        Self {
            map: HashMap::new(),
            capacity,
            per_second: capacity / 60.0,
            max: max.max(1),
        }
    }

    fn refilled(&self, bucket: Bucket, now: Instant) -> Bucket {
        let elapsed = now.saturating_duration_since(bucket.at).as_secs_f64();
        Bucket {
            tokens: (bucket.tokens + elapsed * self.per_second).min(self.capacity),
            at: now,
        }
    }

    /// Time until `bucket` holds one token.
    fn wait(&self, bucket: Bucket) -> Duration {
        Duration::try_from_secs_f64((1.0 - bucket.tokens).max(0.0) / self.per_second).unwrap_or(Duration::MAX)
    }

    fn current(&mut self, client: IpAddr, now: Instant) -> Bucket {
        let fresh = Bucket {
            tokens: self.capacity,
            at: now,
        };
        if let Some(bucket) = self.map.get(&client) {
            return self.refilled(*bucket, now);
        }
        if self.map.len() >= self.max {
            self.evict(now);
        }
        fresh
    }

    /// Forget clients whose bucket is full again (they are like new ones); if that frees nothing,
    /// the least recently seen.
    fn evict(&mut self, now: Instant) {
        let (capacity, per_second) = (self.capacity, self.per_second);
        self.map.retain(|_, b| {
            b.tokens + now.saturating_duration_since(b.at).as_secs_f64() * per_second < capacity
        });
        while self.map.len() >= self.max {
            let Some(oldest) = self.map.iter().min_by_key(|(_, b)| b.at).map(|(ip, _)| *ip) else {
                break;
            };
            self.map.remove(&oldest);
        }
    }

    /// Take a token, or say how long until one is there.
    fn take(&mut self, client: IpAddr, now: Instant) -> Result<(), Duration> {
        let mut bucket = self.current(client, now);
        let result = if bucket.tokens >= 1.0 {
            bucket.tokens -= 1.0;
            Ok(())
        } else {
            Err(self.wait(bucket))
        };
        self.map.insert(client, bucket);
        result
    }

    /// How long the client is still blocked (`None`: not blocked), without taking a token.
    fn blocked(&mut self, client: IpAddr, now: Instant) -> Option<Duration> {
        let bucket = self.map.get(&client).map(|b| self.refilled(*b, now))?;
        (bucket.tokens < 1.0).then(|| self.wait(bucket))
    }
}

/// The rate limiter shared by every request.
#[derive(Debug)]
pub struct Guard {
    policy: ClientPolicy,
    requests: Mutex<Buckets>,
    failures: Mutex<Buckets>,
}

/// Where buckets are kept: the address itself, IPv6 addresses by their /64 (one subscriber).
fn bucket_key(ip: IpAddr) -> IpAddr {
    match canonical(ip) {
        IpAddr::V6(v6) => IpAddr::V6(Ipv6Addr::from(u128::from(v6) & !u128::from(u64::MAX))),
        v4 @ IpAddr::V4(_) => v4,
    }
}

impl Guard {
    #[must_use]
    pub fn new(policy: ClientPolicy) -> Self {
        Self {
            requests: Mutex::new(Buckets::new(policy.requests_per_minute, policy.max_clients)),
            failures: Mutex::new(Buckets::new(policy.auth_failures_per_minute, policy.max_clients)),
            policy,
        }
    }

    /// Whether `client` is on a network that is never limited (`limits.exempt`).
    #[must_use]
    pub fn is_exempt(&self, client: IpAddr) -> bool {
        self.policy.exempt.iter().any(|net| net.contains(client))
    }

    #[must_use]
    pub fn is_trusted_proxy(&self, ip: IpAddr) -> bool {
        self.policy.trusted_proxies.iter().any(|net| net.contains(ip))
    }

    /// The client's address: the peer, or for a trusted proxy the right-most address in
    /// `X-Forwarded-For` that is not itself a trusted proxy.
    #[must_use]
    pub fn client(&self, peer: IpAddr, headers: &HeaderMap) -> IpAddr {
        let peer = canonical(peer);
        if !self.is_trusted_proxy(peer) {
            return peer;
        }
        let hops: Vec<&str> = headers
            .get_all("x-forwarded-for")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .flat_map(|v| v.split(','))
            .collect();
        let mut client = peer;
        for hop in hops.iter().rev() {
            let Ok(ip) = hop.trim().parse::<IpAddr>().map(canonical) else {
                break;
            };
            client = ip;
            if !self.is_trusted_proxy(ip) {
                break;
            }
        }
        client
    }

    /// Count a request; `Err(wait)` when the client is over its request rate.
    pub fn request(&self, client: IpAddr) -> Result<(), Duration> {
        self.request_at(client, Instant::now())
    }

    fn request_at(&self, client: IpAddr, now: Instant) -> Result<(), Duration> {
        if self.is_exempt(client) {
            return Ok(());
        }
        self.requests
            .lock()
            .map_or(Ok(()), |mut b| b.take(bucket_key(client), now))
    }

    /// `Some(wait)` while the client has used up its failed key checks: its requests then get 429
    /// without any key check.
    #[must_use]
    pub fn auth_blocked(&self, client: IpAddr) -> Option<Duration> {
        self.auth_blocked_at(client, Instant::now())
    }

    fn auth_blocked_at(&self, client: IpAddr, now: Instant) -> Option<Duration> {
        if self.is_exempt(client) {
            return None;
        }
        self.failures
            .lock()
            .ok()
            .and_then(|mut b| b.blocked(bucket_key(client), now))
    }

    /// Count a failed key check.
    pub fn auth_failed(&self, client: IpAddr) {
        self.auth_failed_at(client, Instant::now());
    }

    fn auth_failed_at(&self, client: IpAddr, now: Instant) {
        if self.is_exempt(client) {
            return;
        }
        if let Ok(mut buckets) = self.failures.lock() {
            let _ = buckets.take(bucket_key(client), now);
        }
    }

    /// Clients tracked by each limiter (bounded by `max_clients`).
    #[must_use]
    pub fn tracked(&self) -> (usize, usize) {
        (
            self.requests.lock().map_or(0, |b| b.map.len()),
            self.failures.lock().map_or(0, |b| b.map.len()),
        )
    }
}

#[cfg(test)]
mod tests {
    use std::net::IpAddr;
    use std::time::{Duration, Instant};

    use axum::http::{HeaderMap, HeaderValue};

    use super::{ClientPolicy, Guard};

    fn ip(text: &str) -> IpAddr {
        text.parse().unwrap()
    }

    fn guard(requests: u32, failures: u32, max_clients: usize, proxies: &[&str]) -> Guard {
        Guard::new(ClientPolicy {
            requests_per_minute: requests,
            auth_failures_per_minute: failures,
            max_clients,
            trusted_proxies: proxies.iter().map(|p| p.parse().unwrap()).collect(),
            exempt: Vec::new(),
        })
    }

    #[test]
    fn private_networks_are_exempt_by_default() {
        let g = Guard::new(ClientPolicy {
            requests_per_minute: 1,
            auth_failures_per_minute: 1,
            ..ClientPolicy::default()
        });
        let t = Instant::now();
        for client in [
            "127.0.0.1",
            "192.168.1.20",
            "10.1.2.3",
            "172.17.0.2",
            "100.101.102.103",
            "fd7a:115c:a1e0::5",
            "::1",
        ] {
            for _ in 0..50 {
                g.request_at(ip(client), t).unwrap();
                g.auth_failed_at(ip(client), t);
            }
            assert!(g.auth_blocked_at(ip(client), t).is_none(), "{client}");
        }
        assert_eq!(g.tracked(), (0, 0));
        g.request_at(ip("192.0.2.1"), t).unwrap();
        assert!(
            g.request_at(ip("192.0.2.1"), t).is_err(),
            "public addresses are limited"
        );
        assert!(g.request_at(ip("100.128.0.1"), t).is_ok());
        assert!(
            g.request_at(ip("100.128.0.1"), t).is_err(),
            "just outside 100.64.0.0/10"
        );
    }

    #[test]
    fn failed_keys_block_after_the_burst_and_refill_over_time() {
        let g = guard(600, 10, 100, &[]);
        let t = Instant::now();
        let a = ip("192.0.2.1");
        for _ in 0..10 {
            assert!(g.auth_blocked_at(a, t).is_none());
            g.auth_failed_at(a, t);
        }
        let wait = g.auth_blocked_at(a, t).unwrap();
        assert!(
            wait > Duration::from_secs(5) && wait < Duration::from_millis(6100),
            "{wait:?}"
        );
        assert!(g.auth_blocked_at(ip("192.0.2.2"), t).is_none(), "per client");
        assert!(
            g.auth_blocked_at(a, t + Duration::from_millis(6100)).is_none(),
            "a token is back after 6 s"
        );
    }

    #[test]
    fn requests_are_limited_per_client_and_ipv6_per_64() {
        let g = guard(3, 10, 100, &[]);
        let t = Instant::now();
        for _ in 0..3 {
            g.request_at(ip("2001:db8::1"), t).unwrap();
        }
        let wait = g.request_at(ip("2001:db8::ffff"), t).unwrap_err();
        assert!((19..=20).contains(&wait.as_secs()), "{wait:?}");
        g.request_at(ip("2001:db8:0:1::1"), t).unwrap();
        g.request_at(ip("::ffff:192.0.2.9"), t).unwrap();
    }

    #[test]
    fn memory_is_bounded() {
        let g = guard(5, 5, 50, &[]);
        let t = Instant::now();
        for n in 0..5000u32 {
            let client = IpAddr::from(std::net::Ipv4Addr::from(0x0a00_0000 + n));
            let _ = g.request_at(client, t);
            g.auth_failed_at(client, t);
        }
        let (requests, failures) = g.tracked();
        assert!(requests <= 50 && failures <= 50, "{requests} {failures}");
    }

    #[test]
    fn forwarded_for_only_from_trusted_proxies() {
        let g = guard(5, 5, 50, &["127.0.0.1", "10.0.0.0/8"]);
        let mut headers = HeaderMap::new();
        headers.append(
            "x-forwarded-for",
            HeaderValue::from_static("198.51.100.7, 203.0.113.5"),
        );
        headers.append("x-forwarded-for", HeaderValue::from_static("10.1.2.3"));
        assert_eq!(g.client(ip("127.0.0.1"), &headers), ip("203.0.113.5"));
        assert_eq!(g.client(ip("::ffff:127.0.0.1"), &headers), ip("203.0.113.5"));
        assert_eq!(
            g.client(ip("192.0.2.50"), &headers),
            ip("192.0.2.50"),
            "untrusted peer"
        );
        assert_eq!(g.client(ip("127.0.0.1"), &HeaderMap::new()), ip("127.0.0.1"));
        let mut junk = HeaderMap::new();
        junk.insert("x-forwarded-for", HeaderValue::from_static("not-an-ip"));
        assert_eq!(g.client(ip("127.0.0.1"), &junk), ip("127.0.0.1"));
    }
}
