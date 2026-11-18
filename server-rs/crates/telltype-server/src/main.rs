//! `telltype-server`: speech to text and the text models of the Telltype dictation app, in one binary.

use std::process::ExitCode;

use telltype_config::Config;
use telltype_server::{
    Command, ServerError, credentials, fingerprint, parse_args, serve, sherpa_loader, usage,
};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = match parse_args(&args, std::env::var_os("TELLTYPE_CONFIG").map(Into::into)) {
        Ok(command) => command,
        Err(message) => {
            eprintln!("telltype-server: {message}\n\n{}", usage());
            return ExitCode::from(2);
        }
    };
    init_logging();
    let result = match command {
        Command::Help => {
            print!("{}", usage());
            Ok(())
        }
        Command::Version => {
            println!("telltype-server {}", telltype_http::VERSION);
            Ok(())
        }
        Command::CheckConfig { config } => Config::load(&config)
            .map(|_| println!("ok: {}", config.display()))
            .map_err(ServerError::from),
        Command::Credentials {
            config,
            rotate_key,
            rotate_cert,
        } => Config::load(&config)
            .map_err(ServerError::from)
            .and_then(|c| credentials(&c, rotate_key, rotate_cert))
            .map(|report| print!("{report}")),
        Command::Fingerprint { config } => Config::load(&config)
            .map_err(ServerError::from)
            .and_then(|c| fingerprint(&c))
            .map(|report| print!("{report}")),
        Command::Serve { config } => run_server(&config),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            tracing::error!(event = "fatal", error = %error);
            eprintln!("telltype-server: {error}");
            ExitCode::from(error.exit_code())
        }
    }
}

fn run_server(path: &std::path::Path) -> Result<(), ServerError> {
    let config = Config::load(path)?;
    let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;
    runtime.block_on(async {
        let loader = sherpa_loader(&config);
        serve(config, loader, shutdown_signal(), None).await
    })
}

/// SIGTERM (systemd stop) or SIGINT (Ctrl-C).
async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let (Ok(mut term), Ok(mut int)) = (signal(SignalKind::terminate()), signal(SignalKind::interrupt()))
    else {
        // Without signal handlers the process can still be stopped by SIGKILL; keep serving.
        std::future::pending::<()>().await;
        return;
    };
    tokio::select! {
        _ = term.recv() => {}
        _ = int.recv() => {}
    }
}

/// One JSON object per line on stdout (the journal). Level from `TELLTYPE_LOG`, default `info`.
fn init_logging() {
    let filter = tracing_subscriber::EnvFilter::try_from_env("TELLTYPE_LOG")
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    let _ = tracing_subscriber::fmt()
        .json()
        .flatten_event(true)
        .with_current_span(false)
        .with_env_filter(filter)
        .with_writer(std::io::stdout)
        .try_init();
}
