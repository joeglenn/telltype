//! Start-up, serving and shutdown.

use std::fmt::Write as _;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use telltype_config::{Config, ConfigError, SuperviseConfig, TlsMode, UpstreamConfig};
use telltype_http::credentials::{self, CredentialError, Fingerprints, Outcome};
use telltype_http::{AppState, HttpLimits, router};
use telltype_speech::{EngineLimits, LoadError, Recognizer, SherpaOptions, SherpaRecognizer, SpeechEngine};
use telltype_supervisor::{PresetError, ProcessSpec, Supervisor, build_presets, write_presets};
use telltype_upstream::{UpstreamError, UpstreamSpec, Upstreams};

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
    let key = config
        .auth
        .key_file
        .as_deref()
        .map(credentials::load_key)
        .transpose()?;
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

    let state = AppState {
        key: key.map(Arc::new),
        speech,
        speech_model: config.speech.model_name.clone(),
        upstreams: Arc::new(upstreams),
        limits: HttpLimits {
            max_upload_bytes: config.server.max_upload_bytes,
            multipart_overhead_bytes: config.server.multipart_overhead_bytes,
            max_json_bytes: config.server.max_json_bytes,
            retry_after_s: config.server.retry_after.as_secs(),
        },
    };
    let app = router(state).into_make_service_with_connect_info::<SocketAddr>();
    let addr = config.server.listen;
    let listener =
        std::net::TcpListener::bind(addr).map_err(|source| ServerError::Listen { addr, source })?;
    listener.set_nonblocking(true)?;
    let bound = listener.local_addr()?;
    let handle = axum_server::Handle::new();
    let server = {
        let handle = handle.clone();
        async move {
            match tls {
                Some(rustls) => {
                    axum_server::from_tcp_rustls(listener, rustls)?
                        .handle(handle)
                        .serve(app)
                        .await
                }
                None => axum_server::from_tcp(listener)?.handle(handle).serve(app).await,
            }
        }
    };
    let mut server = tokio::spawn(server);
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
    for supervisor in supervisors {
        supervisor.stop().await;
    }
    tracing::info!(event = "stopped");
    result
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
