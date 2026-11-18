//! Telltype Server: wiring the configuration, credentials, speech engine, upstreams and supervised
//! `llama-server` processes into one running HTTP(S) server with graceful shutdown.

mod cli;
mod run;

pub use cli::{Command, parse_args, usage};
pub use run::{Loader, ServerError, credentials, fingerprint, serve, sherpa_loader};
