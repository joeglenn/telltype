//! Start-up, serving and shutdown.

use std::fmt::Write as _;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use telltype_config::{Config, ConfigError, ConnectionLimits, SuperviseConfig, TlsMode, UpstreamConfig};
use telltype_http::credentials::{self, CredentialError, Fingerprints, Outcome};
use telltype_http::keys::{self as named_keys, KeyStore};
use telltype_http::{AppState, ClientPolicy, Guard, HttpLimits, router};
use telltype_speech::{EngineLimits, LoadError, Recognizer, SherpaOptions, SherpaRecognizer, SpeechEngine};
use telltype_supervisor::{PresetError, ProcessSpec, Supervisor, build_presets, write_presets};
use telltype_upstream::{UpstreamError, UpstreamSpec, Upstreams};

use crate::cli::KeysAction;
use crate::conn::{ConnLimits, LimitAcceptor};

/// How often the key files are checked for changes (SIGHUP reloads at once).
const KEY_POLL: std::time::Duration = std::time::Duration::from_secs(2);

#[derive(Debug, thiserror::Error)]
pub enum ServerError {
    #[error("configuration: {0}")]
    Config(#[from] ConfigError),
    #[error("credentials: {0}")]
    Credentials(#[from] CredentialError),
    #[error("upstreams: {0}")]
    Upstream(#[from] UpstreamError),
    #[error("presets: {0}")]
    Presets(#[from] PresetError),
    #[error("the speech model could not be loaded: {0}")]
    ModelLoad(#[from] LoadError),
    #[error("cannot listen on {addr}: {source}")]
    Listen {
        addr: SocketAddr,
        source: std::io::Error,
    },
    #[error("server: {0}")]
    Io(#[from] std::io::Error),
}

impl ServerError {
    /// Exit code: 2 for configuration and credential problems (systemd shows them as such), else 1.
    #[must_use]
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::Config(_) | Self::Credentials(_) | Self::Presets(_) => 2,
            _ => 1,
        }
    }
}

/// Loads the recogniser on a blocking thread (about a second for Parakeet).
pub type Loader = Box<dyn FnOnce() -> Result<Arc<dyn Recognizer>, LoadError> + Send>;

/// The real loader: Parakeet through sherpa-onnx as configured.
#[must_use]
pub fn sherpa_loader(config: &Config) -> Loader {
    let options = SherpaOptions {
        model_dir: config.speech.model_dir.clone(),
        threads: config.speech.threads,
        hotwords: config
            .speech
            .hotwords
            .as_ref()
            .map(|h| (h.score, h.vocab_dir.clone())),
    };
    Box::new(move || SherpaRecognizer::load(&options).map(|r| Arc::new(r) as Arc<dyn Recognizer>))
}

/// `credentials` command: create or rotate the key and the self-signed certificate.
pub fn credentials(config: &Config, rotate_key: bool, rotate_cert: bool) -> Result<String, ServerError> {
    let mut report = String::new();
    match &config.auth.key_file {
        Some(path) => {
            let outcome = credentials::ensure_key(path, rotate_key)?;
            let _ = writeln!(report, "key: {outcome} ({})", path.display());
        }
        None if config.auth.keys_dir.is_some() => {
            report.push_str("key: none (named keys: telltype-server keys add NAME)\n");
        }
        None => report.push_str("key: none (auth.open = true)\n"),
    }
    let tls = &config.tls;
    match tls.mode {
        TlsMode::SelfSigned => {
            let outcome = credentials::ensure_certificate(
                &tls.cert_file,
                &tls.key_file,
                &tls.common_name,
                &tls.subject_alt_names,
                rotate_cert,
            )?;
            let _ = writeln!(report, "certificate: {outcome} ({})", tls.cert_file.display());
            report.push_str(&fingerprint_lines(&credentials::fingerprints(&tls.cert_file)?));
        }
        TlsMode::Files => {
            credentials::require_certificate(&tls.cert_file, &tls.key_file)?;
            let _ = writeln!(
                report,
                "certificate: kept, tls.mode = \"files\" ({})",
                tls.cert_file.display()
            );
            report.push_str(&fingerprint_lines(&credentials::fingerprints(&tls.cert_file)?));
        }
        TlsMode::Off => report.push_str("certificate: none (tls.mode = \"off\")\n"),
    }
    Ok(report)
}

/// `keys` command: add, list or revoke a named key in `auth.keys_dir`. Never prints a key unless
/// `keys add --show` asks for it.
pub fn keys(config: &Config, action: &KeysAction) -> Result<String, ServerError> {
    let dir = config.auth.keys_dir.as_deref().ok_or_else(|| ConfigError {
        key: "auth.keys_dir".to_owned(),
        message: "is not set; named keys live in that directory".to_owned(),
    })?;
    let mut report = String::new();
    match action {
        KeysAction::Add { name, roles, show } => {
            let path = named_keys::add_key(dir, name, roles.as_deref())?;
            let roles = roles
                .as_ref()
                .map_or_else(|| "all roles".to_owned(), |r| r.join(", "));
            let _ = writeln!(
                report,
                "added key {name} ({roles}) in {}; a running server accepts it within {} s",
                path.display(),
                KEY_POLL.as_secs()
            );
            if *show {
                let (key, _) = named_keys::read_key_file(&path)?;
                let _ = writeln!(report, "{}", String::from_utf8_lossy(&key));
            }
        }
        KeysAction::List => {
            for info in named_keys::list_keys(dir)? {
                let roles = info
                    .roles
                    .map_or_else(|| "all roles".to_owned(), |r| r.join(", "));
                match info.problem {
                    None => {
                        let _ = writeln!(report, "{}\t{roles}", info.name);
                    }
                    Some(problem) => {
                        let _ = writeln!(report, "{}\tNOT USED: {problem}", info.name);
                    }
                }
            }
        }
        KeysAction::Revoke { name } => {
            let path = named_keys::revoke_key(dir, name)?;
            let _ = writeln!(
                report,
                "revoked key {name} (deleted {}); a running server refuses it within {} s",
                path.display(),
                KEY_POLL.as_secs()
            );
        }
    }
    Ok(report)
}

/// Re-read the keys when their files change, and at once on SIGHUP.
async fn watch_keys(store: Arc<KeyStore>) {
    use tokio::signal::unix::{SignalKind, signal};
    let mut hangup = signal(SignalKind::hangup()).ok();
    loop {
        let forced = if let Some(hangup) = hangup.as_mut() {
            tokio::select! {
                _ = hangup.recv() => true,
                () = tokio::time::sleep(KEY_POLL) => false,
            }
        } else {
            tokio::time::sleep(KEY_POLL).await;
            false
        };
        let result = if forced {
            Some(store.reload())
        } else {
            store.reload_if_changed()
        };
        match result {
            Some(Ok(reloaded)) => {
                tracing::info!(event = "keys_reloaded", keys = reloaded.keys, names = ?store.names(), skipped = ?reloaded.skipped);
            }
            Some(Err(error)) => tracing::warn!(event = "keys_reload_failed", error = %error),
            None => {}
        }
    }
}

/// `fingerprint` command.
pub fn fingerprint(config: &Config) -> Result<String, ServerError> {
    Ok(fingerprint_lines(&credentials::fingerprints(
        &config.tls.cert_file,
    )?))
}

fn fingerprint_lines(prints: &Fingerprints) -> String {
    format!(
        "cert sha256 (hex):    {}\nspki sha256 (base64): sha256/{}\n",
        prints.cert_sha256_hex, prints.spki_sha256_base64
    )
}

/// Run until `shutdown` resolves, then finish requests within `server.shutdown_grace_s` and stop
/// the supervised processes. `listening` receives the bound address (useful with port 0).
pub async fn serve(
    config: Config,
    loader: Loader,
    shutdown: impl std::future::Future<Output = ()> + Send,
    listening: Option<tokio::sync::oneshot::Sender<SocketAddr>>,
) -> Result<(), ServerError> {
    let keys = load_keys(&config)?;
    let tls = prepare_tls(&config)?;
    let upstreams = Upstreams::new(
        config.upstreams().into_iter().map(upstream_spec).collect(),
        config.server.health_timeout,
    )?;
    let supervisors = start_supervisors(&config)?;

    let speech = Arc::new(SpeechEngine::new(EngineLimits {
        max_concurrent: config.speech.max_concurrent,
        max_waiting: config.speech.max_waiting,
        max_audio_seconds: config.speech.max_audio_seconds,
        recognition_timeout: config.speech.recognition_timeout,
        max_hotwords: config.speech.hotwords.as_ref().map(|h| h.max_terms),
    }));
    let loading = tokio::task::spawn_blocking({
        let speech = Arc::clone(&speech);
        let model = config.speech.model_name.clone();
        move || {
            let started = Instant::now();
            let recognizer = loader()?;
            speech.set_recognizer(recognizer);
            tracing::info!(event = "model_loaded", model = %model, ms = started.elapsed().as_millis());
            Ok::<(), LoadError>(())
        }
    });

    let watcher = keys
        .as_ref()
        .filter(|_| config.auth.keys_dir.is_some())
        .map(|store| tokio::spawn(watch_keys(Arc::clone(store))));
    let state = app_state(&config, keys, speech, upstreams);
    let app = router(state).into_make_service_with_connect_info::<SocketAddr>();
    let addr = config.server.listen;
    let listener =
        std::net::TcpListener::bind(addr).map_err(|source| ServerError::Listen { addr, source })?;
    listener.set_nonblocking(true)?;
    let bound = listener.local_addr()?;
    let handle = axum_server::Handle::new();
    let mut server = tokio::spawn(http_server(&config, tls, listener, handle.clone(), app));
    tracing::info!(event = "listening", addr = %bound, tls = config.tls.mode != TlsMode::Off);
    if let Some(sender) = listening {
        let _ = sender.send(bound);
    }

    // Only a failed load ends the server; a successful one just makes it ready.
    let failed_load = async {
        match loading.await {
            Ok(Err(error)) => error,
            _ => std::future::pending().await,
        }
    };
    let grace = config.server.shutdown_grace;
    let result = tokio::select! {
        outcome = &mut server => joined(outcome),
        error = failed_load => {
            handle.shutdown();
            let _ = server.await;
            Err(ServerError::ModelLoad(error))
        }
        () = shutdown => {
            tracing::info!(event = "shutting_down", grace_s = grace.as_secs_f64(), connections = handle.connection_count());
            // Stop accepting, let running requests finish within the grace period, then close.
            handle.graceful_shutdown(Some(grace));
            joined(server.await)
        }
    };
    if let Some(watcher) = watcher {
        watcher.abort();
    }
    for supervisor in supervisors {
        supervisor.stop().await;
    }
    tracing::info!(event = "stopped");
    result
}

fn load_keys(config: &Config) -> Result<Option<Arc<KeyStore>>, ServerError> {
    let auth = &config.auth;
    if auth.key_file.is_none() && auth.keys_dir.is_none() {
        return Ok(None);
    }
    let store = KeyStore::load(auth.key_file.as_deref(), auth.keys_dir.as_deref())?;
    tracing::info!(event = "keys_loaded", names = ?store.names());
    Ok(Some(Arc::new(store)))
}

fn app_state(
    config: &Config,
    keys: Option<Arc<KeyStore>>,
    speech: Arc<SpeechEngine>,
    upstreams: Upstreams,
) -> AppState {
    let rate = &config.server.rate;
    let guard = Guard::new(ClientPolicy {
        requests_per_minute: rate.requests_per_minute,
        auth_failures_per_minute: rate.auth_failures_per_minute,
        max_clients: rate.max_clients,
        trusted_proxies: rate.trusted_proxies.clone(),
        exempt: rate.exempt.clone(),
    });
    AppState {
        keys,
        guard: Arc::new(guard),
        speech,
        speech_model: config.speech.model_name.clone(),
        upstreams: Arc::new(upstreams),
        limits: HttpLimits {
            max_upload_bytes: config.server.max_upload_bytes,
            multipart_overhead_bytes: config.server.multipart_overhead_bytes,
            max_json_bytes: config.server.max_json_bytes,
            retry_after_s: config.server.retry_after.as_secs(),
            body_grace: config.server.limits.body_grace,
            body_min_bytes_per_s: config.server.limits.body_min_bytes_per_s,
        },
    }
}

type App = axum::extract::connect_info::IntoMakeServiceWithConnectInfo<axum::Router, SocketAddr>;

/// The listener with the connection caps, TLS (when configured) and the HTTP limits.
fn http_server(
    config: &Config,
    tls: Option<axum_server::tls_rustls::RustlsConfig>,
    listener: std::net::TcpListener,
    handle: axum_server::Handle<SocketAddr>,
    app: App,
) -> impl std::future::Future<Output = std::io::Result<()>> + use<> {
    let limits = config.server.limits.clone();
    let acceptor = LimitAcceptor::new(ConnLimits {
        max_total: limits.max_connections,
        max_per_ip: limits.max_connections_per_ip,
        idle: limits.idle_timeout,
        exempt: config
            .server
            .rate
            .trusted_proxies
            .iter()
            .chain(&config.server.rate.exempt)
            .copied()
            .collect(),
    });
    async move {
        if let Some(rustls) = tls {
            let tls = axum_server::tls_rustls::RustlsAcceptor::new(rustls)
                .handshake_timeout(limits.header_timeout)
                .acceptor(acceptor);
            let mut server = axum_server::from_tcp(listener)?.acceptor(tls);
            tune(server.http_builder(), &limits);
            server
                .first_request_timeout(limits.header_timeout)
                .handle(handle)
                .serve(app)
                .await
        } else {
            let mut server = axum_server::from_tcp(listener)?.acceptor(acceptor);
            tune(server.http_builder(), &limits);
            server
                .first_request_timeout(limits.header_timeout)
                .handle(handle)
                .serve(app)
                .await
        }
    }
}

/// HTTP timeouts and limits: the header read timeout (also the HTTP/1 keep-alive idle time, as
/// hyper starts it when it waits for the next request), HTTP/2 stream limits.
fn tune(
    builder: &mut hyper_util::server::conn::auto::Builder<hyper_util::rt::TokioExecutor>,
    limits: &ConnectionLimits,
) {
    builder
        .http1()
        .header_read_timeout(limits.header_timeout)
        .max_buf_size(256 * 1024);
    builder
        .http2()
        .max_concurrent_streams(limits.http2_max_streams)
        .max_pending_accept_reset_streams(Some(20))
        .max_local_error_reset_streams(Some(64))
        .max_header_list_size(64 * 1024);
}

fn joined(served: Result<std::io::Result<()>, tokio::task::JoinError>) -> Result<(), ServerError> {
    match served {
        Ok(result) => result.map_err(ServerError::from),
        Err(_) => Err(ServerError::Io(std::io::Error::other(
            "the server task stopped unexpectedly",
        ))),
    }
}

fn prepare_tls(config: &Config) -> Result<Option<axum_server::tls_rustls::RustlsConfig>, ServerError> {
    let tls = &config.tls;
    match tls.mode {
        TlsMode::Off => return Ok(None),
        TlsMode::Files => credentials::require_certificate(&tls.cert_file, &tls.key_file)?,
        TlsMode::SelfSigned => {
            let outcome = credentials::ensure_certificate(
                &tls.cert_file,
                &tls.key_file,
                &tls.common_name,
                &tls.subject_alt_names,
                false,
            )?;
            if outcome == Outcome::Created {
                tracing::info!(event = "certificate_created", file = %tls.cert_file.display());
            }
        }
    }
    let prints = credentials::fingerprints(&tls.cert_file)?;
    tracing::info!(
        event = "certificate",
        cert_sha256 = %prints.cert_sha256_hex,
        spki_sha256 = %format!("sha256/{}", prints.spki_sha256_base64)
    );
    let certs = read_pem(&tls.cert_file)?;
    let key = read_pem(&tls.key_file)?;
    Ok(Some(axum_server::tls_rustls::RustlsConfig::from_config(
        Arc::new(server_config(&certs, &key)?),
    )))
}

fn read_pem(path: &std::path::Path) -> Result<Vec<u8>, ServerError> {
    std::fs::read(path).map_err(|source| {
        CredentialError::Io {
            path: path.to_path_buf(),
            source,
        }
        .into()
    })
}

/// TLS 1.2 and 1.3 with the ring provider and our certificate.
fn server_config(cert_pem: &[u8], key_pem: &[u8]) -> Result<rustls::ServerConfig, ServerError> {
    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    let bad =
        |what: &str| ServerError::Credentials(CredentialError::Generate(format!("cannot read the {what}")));
    let certs = CertificateDer::pem_slice_iter(cert_pem)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| bad("certificate"))?;
    let key = PrivateKeyDer::from_pem_slice(key_pem).map_err(|_| bad("private key"))?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|_| bad("TLS versions"))?
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|_| bad("certificate and key pair"))?;
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(config)
}

fn upstream_spec(config: &UpstreamConfig) -> UpstreamSpec {
    UpstreamSpec {
        role: config.role.clone(),
        url: config.url.clone(),
        model: config.model.clone(),
        aliases: config.aliases.clone(),
        timeout: config.timeout,
        max_in_flight: config.max_in_flight,
        health_path: config.health_path.clone(),
        allow: config.allow.clone(),
    }
}

fn start_supervisors(config: &Config) -> Result<Vec<Supervisor>, ServerError> {
    let mut supervisors = Vec::new();
    for upstream in config.upstreams() {
        let Some(supervise) = &upstream.supervise else {
            continue;
        };
        let spec = process_spec(upstream, supervise)?;
        tracing::info!(event = "supervising", process = %spec.name, program = %spec.program.display());
        supervisors.push(Supervisor::spawn(spec));
    }
    Ok(supervisors)
}

/// The command line for a supervised `llama-server`: the configured arguments, the address from
/// the URL and, in router mode, the generated presets file.
fn process_spec(upstream: &UpstreamConfig, supervise: &SuperviseConfig) -> Result<ProcessSpec, ServerError> {
    let address = upstream.url.trim_start_matches("http://");
    let (host, port) = address.rsplit_once(':').unwrap_or((address, "80"));
    let mut args = supervise.args.clone();
    args.extend([
        "--host".to_owned(),
        host.to_owned(),
        "--port".to_owned(),
        port.to_owned(),
    ]);
    if let Some(router) = &supervise.router {
        let presets = build_presets(&router.model_dirs, router.models_ini.as_deref(), &router.defaults)?;
        write_presets(&router.presets_file, &presets)?;
        tracing::info!(event = "presets_written", process = %upstream.role, models = presets.names.len(), file = %router.presets_file.display());
        args.extend([
            "--models-preset".to_owned(),
            router.presets_file.display().to_string(),
            "--models-max".to_owned(),
            router.models_max.to_string(),
        ]);
    }
    let mut spec = ProcessSpec::new(
        &upstream.role,
        supervise.binary.clone(),
        args,
        format!("{}{}", upstream.url, upstream.health_path),
    );
    spec.restart_min = supervise.restart_min;
    spec.restart_max = supervise.restart_max;
    spec.stop_timeout = supervise.stop_timeout;
    Ok(spec)
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use telltype_config::Config;

    use super::{ServerError, process_spec};

    #[test]
    fn supervised_command_lines() {
        let dir = std::env::temp_dir().join(format!("telltype-run-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("models")).unwrap();
        std::fs::write(dir.join("models/Qwen3.5-4B.gguf"), b"x").unwrap();
        let text = format!(
            "[auth]\nopen = true\n[speech]\nmodel_dir = \"/m\"\n\
             [text.cleanup]\nmodel = \"c\"\nurl = \"http://127.0.0.1:18172\"\n\
             [text.cleanup.supervise]\nbinary = \"/bin/llama-server\"\nargs = [\"--model\", \"/c.gguf\"]\n\
             [text.commands]\nmodel = \"q\"\nurl = \"http://127.0.0.1:18173\"\n\
             [text.commands.supervise]\nbinary = \"/bin/llama-server\"\n\
             [text.commands.supervise.router]\nmodel_dirs = [\"{0}/models\"]\npresets_file = \"{0}/presets.ini\"\n",
            dir.display()
        );
        let config = Config::parse(&text, Path::new("/"), None).unwrap();
        let cleanup = config.cleanup.clone();
        let spec = process_spec(&cleanup, cleanup.supervise.as_ref().unwrap()).unwrap();
        assert_eq!(
            spec.args,
            ["--model", "/c.gguf", "--host", "127.0.0.1", "--port", "18172"]
        );
        assert_eq!(spec.health_url, "http://127.0.0.1:18172/health");
        let commands = config.commands.clone().unwrap();
        let spec = process_spec(&commands, commands.supervise.as_ref().unwrap()).unwrap();
        let presets = format!("{}/presets.ini", dir.display());
        assert_eq!(
            spec.args,
            [
                "--host",
                "127.0.0.1",
                "--port",
                "18173",
                "--models-preset",
                presets.as_str(),
                "--models-max",
                "1"
            ]
        );
        assert!(
            std::fs::read_to_string(&presets)
                .unwrap()
                .contains("[qwen3.5-4b]")
        );
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(ServerError::Io(std::io::Error::other("x")).exit_code(), 1);
    }
}
