//! The configuration of Telltype Server: one TOML file, read and validated once at start.
//!
//! Every key is documented in `server-rs/README.md` ("Configuration reference"). Unknown keys are
//! errors, and every error names the key it is about, so a typo never silently falls back to a
//! default. Relative paths are resolved against the directory of the configuration file, and a
//! leading `~/` against `$HOME`.

use std::collections::BTreeMap;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// A configuration problem. `key` is the dotted TOML key it is about (empty when the problem is the
/// file itself, for example a TOML syntax error, whose message then carries line and column).
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("{}{message}", if key.is_empty() { String::new() } else { format!("{key}: ") })]
pub struct ConfigError {
    pub key: String,
    pub message: String,
}

impl ConfigError {
    fn new(key: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            key: key.into(),
            message: message.into(),
        }
    }
}

/// The whole configuration, validated.
#[derive(Debug, Clone, PartialEq)]
pub struct Config {
    pub server: ServerConfig,
    pub tls: TlsConfig,
    pub auth: AuthConfig,
    pub speech: SpeechConfig,
    pub cleanup: UpstreamConfig,
    pub commands: Option<UpstreamConfig>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ServerConfig {
    pub listen: SocketAddr,
    pub max_upload_bytes: usize,
    pub multipart_overhead_bytes: usize,
    pub max_json_bytes: usize,
    pub retry_after: Duration,
    pub health_timeout: Duration,
    pub shutdown_grace: Duration,
    pub limits: ConnectionLimits,
    pub rate: RateLimits,
}

/// Connection handling: timeouts and caps against slow and idle clients.
#[derive(Debug, Clone, PartialEq)]
pub struct ConnectionLimits {
    /// Time for a client to finish the TLS handshake and each request's headers (HTTP/1), or its
    /// first request (HTTP/2). Also how long an idle HTTP/1 keep-alive connection is kept.
    pub header_timeout: Duration,
    /// A connection with no bytes read or written for this long is closed.
    pub idle_timeout: Duration,
    /// A request body must keep arriving at `body_min_bytes_per_s` on average after this grace.
    pub body_grace: Duration,
    pub body_min_bytes_per_s: u64,
    pub max_connections: usize,
    /// Open connections per client address (IPv6: per /64). Trusted proxies are exempt.
    pub max_connections_per_ip: usize,
    pub http2_max_streams: u32,
}

/// Per-client token buckets.
#[derive(Debug, Clone, PartialEq)]
pub struct RateLimits {
    /// Requests per minute per client (also the burst).
    pub requests_per_minute: u32,
    /// Failed key checks per minute per client (also the burst); then 429 without any key check.
    pub auth_failures_per_minute: u32,
    /// Clients tracked at most (memory bound); the oldest are forgotten first.
    pub max_clients: usize,
    /// Peers whose `X-Forwarded-For` is believed (a reverse proxy on this machine, say).
    pub trusted_proxies: Vec<IpNet>,
    /// Clients never rate-limited or capped per address: by default loopback, the private LAN
    /// ranges, Tailscale and Docker bridges ([`DEFAULT_EXEMPT`]); `exempt = []` for a public server.
    pub exempt: Vec<IpNet>,
}

/// The default `limits.exempt`: loopback, RFC 1918 (which includes Docker's 172.16.0.0/12),
/// Tailscale's CGNAT range and IPv6 ULA prefix, IPv6 link-local and unique-local addresses.
pub const DEFAULT_EXEMPT: &[&str] = &[
    "127.0.0.0/8",
    "::1",
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "100.64.0.0/10",
    "fd7a:115c:a1e0::/48",
    "fe80::/10",
    "fc00::/7",
];

/// An address range such as `127.0.0.1/32` or `fd00::/8`; a bare address is a single host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IpNet {
    pub addr: IpAddr,
    pub prefix: u8,
}

impl IpNet {
    #[must_use]
    pub fn contains(&self, ip: IpAddr) -> bool {
        match (self.addr, canonical(ip)) {
            (IpAddr::V4(net), IpAddr::V4(ip)) => {
                let mask = u32::MAX.checked_shl(32 - u32::from(self.prefix)).unwrap_or(0);
                u32::from(net) & mask == u32::from(ip) & mask
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let mask = u128::MAX.checked_shl(128 - u32::from(self.prefix)).unwrap_or(0);
                u128::from(net) & mask == u128::from(ip) & mask
            }
            _ => false,
        }
    }
}

impl std::str::FromStr for IpNet {
    type Err = ();
    fn from_str(text: &str) -> Result<Self, ()> {
        let (addr, prefix) = match text.split_once('/') {
            Some((addr, prefix)) => (addr, Some(prefix.parse::<u8>().map_err(|_| ())?)),
            None => (text, None),
        };
        let addr = canonical(addr.trim().parse::<IpAddr>().map_err(|_| ())?);
        let max = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = prefix.unwrap_or(max);
        if prefix > max {
            return Err(());
        }
        Ok(Self { addr, prefix })
    }
}

/// An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`, as a dual-stack socket reports IPv4 peers) as IPv4.
#[must_use]
pub fn canonical(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(ip, IpAddr::V4),
        IpAddr::V4(_) => ip,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TlsMode {
    /// Certificate and key files that must exist.
    Files,
    /// Like `Files`, but both are generated (ECDSA P-256, self-signed) when neither exists.
    SelfSigned,
    /// Plain HTTP. Only accepted together with `allow_plain_http = true`.
    Off,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TlsConfig {
    pub mode: TlsMode,
    pub cert_file: PathBuf,
    pub key_file: PathBuf,
    pub subject_alt_names: Vec<String>,
    pub common_name: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AuthConfig {
    /// The single key (its name in logs is `default`).
    pub key_file: Option<PathBuf>,
    /// Named keys, one `<name>.key` file each, reloaded without a restart.
    /// Both `None` only when `open = true`: every request is served without a key.
    pub keys_dir: Option<PathBuf>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SpeechConfig {
    pub model_dir: PathBuf,
    pub model_name: String,
    pub threads: u16,
    pub max_concurrent: usize,
    pub max_waiting: usize,
    pub max_audio_seconds: f64,
    pub recognition_timeout: Duration,
    pub hotwords: Option<HotwordsConfig>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HotwordsConfig {
    pub score: f32,
    pub max_terms: usize,
    /// Where the derived `bpe.vocab` (made from `tokens.txt`; never audio) is written.
    pub vocab_dir: PathBuf,
}

/// One OpenAI-compatible text model server (normally `llama-server`).
#[derive(Debug, Clone, PartialEq)]
pub struct UpstreamConfig {
    /// `cleanup` or `commands`.
    pub role: String,
    pub url: String,
    /// The model name `/healthz` reports for this role.
    pub model: String,
    /// Request model name -> model name sent upstream (the same name: the body passes unchanged).
    pub aliases: BTreeMap<String, String>,
    pub timeout: Duration,
    pub max_in_flight: usize,
    pub health_path: String,
    /// Model names clients may use beyond `model` and the aliases (`*` and `?` globs). Default
    /// `["*"]` (everything the upstream lists); empty: the configured model only. Anything else is 404 `model_not_found` before the upstream sees it.
    pub allow: Vec<String>,
    pub supervise: Option<SuperviseConfig>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SuperviseConfig {
    pub binary: PathBuf,
    pub args: Vec<String>,
    pub restart_min: Duration,
    pub restart_max: Duration,
    pub stop_timeout: Duration,
    pub router: Option<RouterConfig>,
}

/// `llama-server` in router mode over a presets file generated at start.
#[derive(Debug, Clone, PartialEq)]
pub struct RouterConfig {
    pub model_dirs: Vec<PathBuf>,
    pub models_ini: Option<PathBuf>,
    pub presets_file: PathBuf,
    pub models_max: u32,
    pub defaults: BTreeMap<String, String>,
}

impl Config {
    /// Read and validate the file at `path`.
    pub fn load(path: &Path) -> Result<Self, ConfigError> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| ConfigError::new("", format!("cannot read {}: {e}", path.display())))?;
        // Relative paths in the file are relative to the file's own directory, whatever the cwd.
        let absolute = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
        let base = absolute
            .parent()
            .map_or_else(|| PathBuf::from("/"), Path::to_path_buf);
        Self::parse(
            &text,
            &base,
            std::env::var_os("HOME").map(PathBuf::from).as_deref(),
        )
    }

    /// Parse and validate TOML text. Relative paths resolve against `base`, `~/` against `home`.
    pub fn parse(text: &str, base: &Path, home: Option<&Path>) -> Result<Self, ConfigError> {
        let raw: raw::File =
            toml::from_str(text).map_err(|e| ConfigError::new("", format!("invalid TOML: {e}")))?;
        let paths = Paths { base, home };
        let plain_http_confirmed = raw.tls.as_ref().and_then(|t| t.allow_plain_http).unwrap_or(false);
        let server = server(&raw.server.unwrap_or_default(), &raw.limits.unwrap_or_default())?;
        let tls = tls(raw.tls.unwrap_or_default(), &paths)?;
        if tls.mode == TlsMode::Off && !plain_http_confirmed {
            return Err(ConfigError::new(
                "tls.allow_plain_http",
                "tls.mode = \"off\" serves plain HTTP; set allow_plain_http = true to confirm",
            ));
        }
        let auth = auth(raw.auth.unwrap_or_default(), &paths)?;
        let speech = speech(raw.speech.ok_or_else(|| missing("speech"))?, &paths)?;
        let text_section = raw.text.ok_or_else(|| missing("text"))?;
        let cleanup = upstream(
            "cleanup",
            text_section.cleanup.ok_or_else(|| missing("text.cleanup"))?,
            &paths,
            &Defaults {
                url: "http://127.0.0.1:8172",
                timeout_s: 60.0,
                in_flight: 4,
            },
        )?;
        let commands = text_section
            .commands
            .map(|c| {
                upstream(
                    "commands",
                    c,
                    &paths,
                    &Defaults {
                        url: "http://127.0.0.1:8173",
                        timeout_s: 120.0,
                        in_flight: 2,
                    },
                )
            })
            .transpose()?;
        if let Some(commands) = &commands
            && let Some(alias) = commands.aliases.keys().find(|a| cleanup.aliases.contains_key(*a))
        {
            return Err(ConfigError::new(
                format!("text.commands.aliases.{alias}"),
                "the same alias is already defined in text.cleanup.aliases",
            ));
        }
        let longest = config_longest_wait(&speech, &cleanup, commands.as_ref());
        if server.limits.idle_timeout <= longest {
            return Err(ConfigError::new(
                "limits.idle_timeout_s",
                format!(
                    "must be longer than the longest request wait ({} s: recognition_timeout_s and the text timeouts)",
                    longest.as_secs_f64()
                ),
            ));
        }
        Ok(Self {
            server,
            tls,
            auth,
            speech,
            cleanup,
            commands,
        })
    }

    /// The upstreams in a fixed order: cleanup, then commands if configured.
    #[must_use]
    pub fn upstreams(&self) -> Vec<&UpstreamConfig> {
        std::iter::once(&self.cleanup)
            .chain(self.commands.as_ref())
            .collect()
    }
}

/// The longest a request may wait with no bytes on its connection.
fn config_longest_wait(
    speech: &SpeechConfig,
    cleanup: &UpstreamConfig,
    commands: Option<&UpstreamConfig>,
) -> Duration {
    std::iter::once(speech.recognition_timeout)
        .chain(std::iter::once(cleanup.timeout))
        .chain(commands.map(|c| c.timeout))
        .max()
        .unwrap_or_default()
}

fn missing(key: &str) -> ConfigError {
    ConfigError::new(key, "this section is required")
}

struct Paths<'a> {
    base: &'a Path,
    home: Option<&'a Path>,
}

impl Paths<'_> {
    fn resolve(&self, key: &str, value: &str) -> Result<PathBuf, ConfigError> {
        if value.trim().is_empty() {
            return Err(ConfigError::new(key, "must not be empty"));
        }
        if let Some(rest) = value.strip_prefix("~/") {
            let home = self
                .home
                .ok_or_else(|| ConfigError::new(key, "starts with ~/ but HOME is not set"))?;
            return Ok(home.join(rest));
        }
        let path = PathBuf::from(value);
        Ok(if path.is_absolute() {
            path
        } else {
            self.base.join(path)
        })
    }

    fn required(&self, key: &str, value: Option<&str>) -> Result<PathBuf, ConfigError> {
        self.resolve(key, value.ok_or_else(|| ConfigError::new(key, "is required"))?)
    }
}

fn positive_duration(key: &str, seconds: f64) -> Result<Duration, ConfigError> {
    if !seconds.is_finite() || seconds <= 0.0 || seconds > 86_400.0 {
        return Err(ConfigError::new(
            key,
            "must be a number of seconds above 0 and at most 86400",
        ));
    }
    Ok(Duration::from_secs_f64(seconds))
}

fn at_least_one(key: &str, value: usize) -> Result<usize, ConfigError> {
    if value == 0 {
        return Err(ConfigError::new(key, "must be at least 1"));
    }
    Ok(value)
}

fn server(raw: &raw::Server, limits: &raw::Limits) -> Result<ServerConfig, ConfigError> {
    let listen = raw
        .listen
        .as_deref()
        .unwrap_or("0.0.0.0:8170")
        .parse()
        .map_err(|_| {
            ConfigError::new(
                "server.listen",
                "must be an address with a port, e.g. 0.0.0.0:8170",
            )
        })?;
    let retry_after_s = raw.retry_after_s.unwrap_or(5);
    if retry_after_s == 0 {
        return Err(ConfigError::new("server.retry_after_s", "must be at least 1"));
    }
    Ok(ServerConfig {
        listen,
        max_upload_bytes: at_least_one(
            "server.max_upload_bytes",
            raw.max_upload_bytes.unwrap_or(25 << 20),
        )?,
        multipart_overhead_bytes: raw.multipart_overhead_bytes.unwrap_or(1 << 20),
        max_json_bytes: at_least_one("server.max_json_bytes", raw.max_json_bytes.unwrap_or(1 << 20))?,
        retry_after: Duration::from_secs(retry_after_s),
        health_timeout: positive_duration("server.health_timeout_s", raw.health_timeout_s.unwrap_or(2.0))?,
        shutdown_grace: positive_duration("server.shutdown_grace_s", raw.shutdown_grace_s.unwrap_or(10.0))?,
        limits: ConnectionLimits {
            header_timeout: positive_duration(
                "limits.header_timeout_s",
                limits.header_timeout_s.unwrap_or(10.0),
            )?,
            idle_timeout: positive_duration("limits.idle_timeout_s", limits.idle_timeout_s.unwrap_or(300.0))?,
            body_grace: positive_duration("limits.body_grace_s", limits.body_grace_s.unwrap_or(10.0))?,
            body_min_bytes_per_s: limits.body_min_bytes_per_s.unwrap_or(4096),
            max_connections: at_least_one("limits.max_connections", limits.max_connections.unwrap_or(1024))?,
            max_connections_per_ip: at_least_one(
                "limits.max_connections_per_ip",
                limits.max_connections_per_ip.unwrap_or(32),
            )?,
            http2_max_streams: match limits.http2_max_streams.unwrap_or(32) {
                0 => return Err(ConfigError::new("limits.http2_max_streams", "must be at least 1")),
                n => n,
            },
        },
        rate: rate(limits)?,
    })
}

fn rate(raw: &raw::Limits) -> Result<RateLimits, ConfigError> {
    let per_minute = |key: &str, value: Option<u32>, default: u32| {
        let value = value.unwrap_or(default);
        if value == 0 {
            return Err(ConfigError::new(key, "must be at least 1"));
        }
        Ok(value)
    };
    let nets = |key: &str, list: &[String]| {
        list.iter()
            .map(|text| {
                text.parse::<IpNet>().map_err(|()| {
                    ConfigError::new(
                        key,
                        format!("{text:?} is not an address or range (127.0.0.1, 10.0.0.0/8, ::1)"),
                    )
                })
            })
            .collect::<Result<Vec<_>, _>>()
    };
    let trusted_proxies = nets(
        "limits.trusted_proxies",
        raw.trusted_proxies.as_deref().unwrap_or_default(),
    )?;
    let default_exempt: Vec<String> = DEFAULT_EXEMPT.iter().map(|n| (*n).to_owned()).collect();
    let exempt = nets("limits.exempt", raw.exempt.as_deref().unwrap_or(&default_exempt))?;
    Ok(RateLimits {
        requests_per_minute: per_minute("limits.requests_per_minute", raw.requests_per_minute, 600)?,
        auth_failures_per_minute: per_minute(
            "limits.auth_failures_per_minute",
            raw.auth_failures_per_minute,
            10,
        )?,
        max_clients: at_least_one(
            "limits.rate_limit_clients",
            raw.rate_limit_clients.unwrap_or(10_000),
        )?,
        trusted_proxies,
        exempt,
    })
}

fn tls(raw: raw::Tls, paths: &Paths<'_>) -> Result<TlsConfig, ConfigError> {
    let mode = match raw.mode.as_deref().unwrap_or("self-signed") {
        "self-signed" => TlsMode::SelfSigned,
        "files" => TlsMode::Files,
        "off" => TlsMode::Off,
        _ => {
            return Err(ConfigError::new(
                "tls.mode",
                "must be \"self-signed\", \"files\" or \"off\"",
            ));
        }
    };
    let cert_file = paths.resolve(
        "tls.cert_file",
        raw.cert_file.as_deref().unwrap_or("tls/cert.pem"),
    )?;
    let key_file = paths.resolve("tls.key_file", raw.key_file.as_deref().unwrap_or("tls/key.pem"))?;
    let subject_alt_names = raw
        .subject_alt_names
        .unwrap_or_else(|| vec!["localhost".to_owned(), "127.0.0.1".to_owned()]);
    if subject_alt_names.is_empty() || subject_alt_names.iter().any(|n| n.trim().is_empty()) {
        return Err(ConfigError::new(
            "tls.subject_alt_names",
            "needs at least one name, none empty",
        ));
    }
    let common_name = raw.common_name.unwrap_or_else(|| "telltype-server".to_owned());
    if common_name.trim().is_empty() {
        return Err(ConfigError::new("tls.common_name", "must not be empty"));
    }
    Ok(TlsConfig {
        mode,
        cert_file,
        key_file,
        subject_alt_names,
        common_name,
    })
}

fn auth(raw: raw::Auth, paths: &Paths<'_>) -> Result<AuthConfig, ConfigError> {
    let has_keys = raw.key_file.is_some() || raw.keys_dir.is_some();
    match (has_keys, raw.open.unwrap_or(false)) {
        (true, true) => Err(ConfigError::new(
            "auth.open",
            "set auth.key_file and/or auth.keys_dir, or auth.open = true",
        )),
        (false, false) => Err(ConfigError::new(
            "auth.key_file",
            "is required (or auth.keys_dir, or auth.open = true to serve without a key)",
        )),
        (true, false) => Ok(AuthConfig {
            key_file: raw
                .key_file
                .map(|file| paths.resolve("auth.key_file", &file))
                .transpose()?,
            keys_dir: raw
                .keys_dir
                .map(|dir| paths.resolve("auth.keys_dir", &dir))
                .transpose()?,
        }),
        (false, true) => Ok(AuthConfig {
            key_file: None,
            keys_dir: None,
        }),
    }
}

fn speech(raw: raw::Speech, paths: &Paths<'_>) -> Result<SpeechConfig, ConfigError> {
    let model_dir = paths.required("speech.model_dir", raw.model_dir.as_deref())?;
    let model_name = raw
        .model_name
        .unwrap_or_else(|| "parakeet-unified-en-0.6b-int8".to_owned());
    if model_name.trim().is_empty() {
        return Err(ConfigError::new("speech.model_name", "must not be empty"));
    }
    let threads = raw.threads.unwrap_or(8);
    if threads == 0 {
        return Err(ConfigError::new("speech.threads", "must be at least 1"));
    }
    let max_audio_seconds = raw.max_audio_seconds.unwrap_or(360.0);
    if !max_audio_seconds.is_finite() || max_audio_seconds <= 0.0 || max_audio_seconds > 7200.0 {
        return Err(ConfigError::new(
            "speech.max_audio_seconds",
            "must be above 0 and at most 7200",
        ));
    }
    let hotwords = if raw.hotwords.unwrap_or(false) {
        let vocab_dir = paths.resolve(
            "speech.vocab_dir",
            raw.vocab_dir
                .as_deref()
                .ok_or_else(|| ConfigError::new("speech.vocab_dir", "is required when hotwords = true"))?,
        )?;
        let score = raw.hotwords_score.unwrap_or(1.0);
        if !score.is_finite() || score <= 0.0 {
            return Err(ConfigError::new("speech.hotwords_score", "must be above 0"));
        }
        Some(HotwordsConfig {
            score,
            max_terms: at_least_one("speech.max_hotwords", raw.max_hotwords.unwrap_or(100))?,
            vocab_dir,
        })
    } else {
        None
    };
    Ok(SpeechConfig {
        model_dir,
        model_name,
        threads,
        max_concurrent: at_least_one("speech.max_concurrent", raw.max_concurrent.unwrap_or(2))?,
        max_waiting: raw.max_waiting.unwrap_or(4),
        max_audio_seconds,
        recognition_timeout: positive_duration(
            "speech.recognition_timeout_s",
            raw.recognition_timeout_s.unwrap_or(120.0),
        )?,
        hotwords,
    })
}

struct Defaults {
    url: &'static str,
    timeout_s: f64,
    in_flight: usize,
}

fn upstream(
    role: &str,
    raw: raw::Upstream,
    paths: &Paths<'_>,
    defaults: &Defaults,
) -> Result<UpstreamConfig, ConfigError> {
    let prefix = format!("text.{role}");
    let url = raw
        .url
        .as_deref()
        .unwrap_or(defaults.url)
        .trim_end_matches('/')
        .to_owned();
    // Upstreams are local llama-servers; the client is built without TLS on purpose.
    if !url.starts_with("http://") || url.len() <= "http://".len() {
        return Err(ConfigError::new(
            format!("{prefix}.url"),
            "must be an http:// URL (upstreams normally run on this machine; https is not supported)",
        ));
    }
    let model = raw.model.unwrap_or_default();
    if model.trim().is_empty() {
        return Err(ConfigError::new(
            format!("{prefix}.model"),
            "is required (the model name /healthz reports)",
        ));
    }
    let aliases = raw.aliases.unwrap_or_default();
    if let Some((alias, _)) = aliases
        .iter()
        .find(|(a, t)| a.trim().is_empty() || t.trim().is_empty())
    {
        return Err(ConfigError::new(
            format!("{prefix}.aliases.{alias}"),
            "names must not be empty",
        ));
    }
    let health_path = raw.health_path.unwrap_or_else(|| "/health".to_owned());
    if !health_path.starts_with('/') {
        return Err(ConfigError::new(
            format!("{prefix}.health_path"),
            "must start with /",
        ));
    }
    // Absent: every model the upstream lists (as before 1.1); `[]`: the configured model only.
    let allow = raw.allow.unwrap_or_else(|| vec!["*".to_owned()]);
    if allow.iter().any(|pattern| pattern.trim().is_empty()) {
        return Err(ConfigError::new(
            format!("{prefix}.allow"),
            "patterns must not be empty",
        ));
    }
    let supervise = raw
        .supervise
        .map(|s| supervise(&prefix, s, paths, &url))
        .transpose()?;
    Ok(UpstreamConfig {
        role: role.to_owned(),
        url,
        model,
        aliases,
        timeout: positive_duration(
            &format!("{prefix}.timeout_s"),
            raw.timeout_s.unwrap_or(defaults.timeout_s),
        )?,
        max_in_flight: at_least_one(
            &format!("{prefix}.max_in_flight"),
            raw.max_in_flight.unwrap_or(defaults.in_flight),
        )?,
        health_path,
        allow,
        supervise,
    })
}

fn supervise(
    prefix: &str,
    raw: raw::Supervise,
    paths: &Paths<'_>,
    url: &str,
) -> Result<SuperviseConfig, ConfigError> {
    let key = format!("{prefix}.supervise");
    let binary = paths.required(&format!("{key}.binary"), raw.binary.as_deref())?;
    if !url.starts_with("http://127.0.0.1:") && !url.starts_with("http://localhost:") {
        return Err(ConfigError::new(
            format!("{prefix}.url"),
            "a supervised upstream must be http://127.0.0.1:<port> or http://localhost:<port>",
        ));
    }
    // Arguments starting with ~/ are paths under $HOME (llama-server does not expand them itself).
    let args = raw
        .args
        .unwrap_or_default()
        .into_iter()
        .map(|a| match (a.strip_prefix("~/"), paths.home) {
            (Some(rest), Some(home)) => home.join(rest).display().to_string(),
            _ => a,
        })
        .collect::<Vec<_>>();
    if let Some(bad) = args.iter().find(|a| {
        matches!(
            a.as_str(),
            "--host" | "--port" | "--models-preset" | "--models-max"
        )
    }) {
        return Err(ConfigError::new(
            format!("{key}.args"),
            format!("{bad} is set by the server (from the url and the router section)"),
        ));
    }
    let restart_min = positive_duration(&format!("{key}.restart_min_s"), raw.restart_min_s.unwrap_or(1.0))?;
    let restart_max = positive_duration(&format!("{key}.restart_max_s"), raw.restart_max_s.unwrap_or(60.0))?;
    if restart_max < restart_min {
        return Err(ConfigError::new(
            format!("{key}.restart_max_s"),
            "must not be below restart_min_s",
        ));
    }
    let router = raw
        .router
        .map(|r| router(&format!("{key}.router"), r, paths))
        .transpose()?;
    Ok(SuperviseConfig {
        binary,
        args,
        restart_min,
        restart_max,
        stop_timeout: positive_duration(
            &format!("{key}.stop_timeout_s"),
            raw.stop_timeout_s.unwrap_or(20.0),
        )?,
        router,
    })
}

fn router(key: &str, raw: raw::Router, paths: &Paths<'_>) -> Result<RouterConfig, ConfigError> {
    let model_dirs = raw
        .model_dirs
        .unwrap_or_default()
        .iter()
        .map(|d| paths.resolve(&format!("{key}.model_dirs"), d))
        .collect::<Result<Vec<_>, _>>()?;
    let models_ini = raw
        .models_ini
        .as_deref()
        .map(|p| paths.resolve(&format!("{key}.models_ini"), p))
        .transpose()?;
    if model_dirs.is_empty() && models_ini.is_none() {
        return Err(ConfigError::new(key, "needs model_dirs, models_ini or both"));
    }
    let presets_file = paths.required(&format!("{key}.presets_file"), raw.presets_file.as_deref())?;
    let defaults = raw.defaults.unwrap_or_default();
    if let Some(bad) = defaults
        .keys()
        .find(|k| k.trim().is_empty() || k.contains(['=', '[', ']', '\n']))
    {
        return Err(ConfigError::new(
            format!("{key}.defaults"),
            format!("invalid preset key {bad:?}"),
        ));
    }
    Ok(RouterConfig {
        model_dirs,
        models_ini,
        presets_file,
        models_max: raw.models_max.unwrap_or(1),
        defaults,
    })
}

/// The file as written, before validation. Every table rejects unknown keys.
mod raw {
    use std::collections::BTreeMap;

    use serde::Deserialize;

    #[derive(Deserialize, Default)]
    #[serde(deny_unknown_fields)]
    pub struct File {
        pub server: Option<Server>,
        pub limits: Option<Limits>,
        pub tls: Option<Tls>,
        pub auth: Option<Auth>,
        pub speech: Option<Speech>,
        pub text: Option<Text>,
    }

    #[derive(Deserialize, Default)]
    #[serde(deny_unknown_fields)]
    pub struct Server {
        pub listen: Option<String>,
        pub max_upload_bytes: Option<usize>,
        pub multipart_overhead_bytes: Option<usize>,
        pub max_json_bytes: Option<usize>,
        pub retry_after_s: Option<u64>,
        pub health_timeout_s: Option<f64>,
        pub shutdown_grace_s: Option<f64>,
    }

    #[derive(Deserialize, Default)]
    #[serde(deny_unknown_fields)]
    pub struct Limits {
        pub header_timeout_s: Option<f64>,
        pub idle_timeout_s: Option<f64>,
        pub body_grace_s: Option<f64>,
        pub body_min_bytes_per_s: Option<u64>,
        pub max_connections: Option<usize>,
        pub max_connections_per_ip: Option<usize>,
        pub http2_max_streams: Option<u32>,
        pub requests_per_minute: Option<u32>,
        pub auth_failures_per_minute: Option<u32>,
        pub rate_limit_clients: Option<usize>,
        pub trusted_proxies: Option<Vec<String>>,
        pub exempt: Option<Vec<String>>,
    }

    #[derive(Deserialize, Default)]
    #[serde(deny_unknown_fields)]
    pub struct Tls {
        pub mode: Option<String>,
        pub cert_file: Option<String>,
        pub key_file: Option<String>,
        pub subject_alt_names: Option<Vec<String>>,
        pub common_name: Option<String>,
        pub allow_plain_http: Option<bool>,
    }

    #[derive(Deserialize, Default)]
    #[serde(deny_unknown_fields)]
    pub struct Auth {
        pub key_file: Option<String>,
        pub keys_dir: Option<String>,
        pub open: Option<bool>,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    pub struct Speech {
        pub model_dir: Option<String>,
        pub model_name: Option<String>,
        pub threads: Option<u16>,
        pub max_concurrent: Option<usize>,
        pub max_waiting: Option<usize>,
        pub max_audio_seconds: Option<f64>,
        pub recognition_timeout_s: Option<f64>,
        pub hotwords: Option<bool>,
        pub hotwords_score: Option<f32>,
        pub max_hotwords: Option<usize>,
        pub vocab_dir: Option<String>,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    pub struct Text {
        pub cleanup: Option<Upstream>,
        pub commands: Option<Upstream>,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    pub struct Upstream {
        pub url: Option<String>,
        pub model: Option<String>,
        pub aliases: Option<BTreeMap<String, String>>,
        pub timeout_s: Option<f64>,
        pub max_in_flight: Option<usize>,
        pub health_path: Option<String>,
        pub allow: Option<Vec<String>>,
        pub supervise: Option<Supervise>,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    pub struct Supervise {
        pub binary: Option<String>,
        pub args: Option<Vec<String>>,
        pub restart_min_s: Option<f64>,
        pub restart_max_s: Option<f64>,
        pub stop_timeout_s: Option<f64>,
        pub router: Option<Router>,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    pub struct Router {
        pub model_dirs: Option<Vec<String>>,
        pub models_ini: Option<String>,
        pub presets_file: Option<String>,
        pub models_max: Option<u32>,
        pub defaults: Option<BTreeMap<String, String>>,
    }
}

#[cfg(test)]
mod tests;
