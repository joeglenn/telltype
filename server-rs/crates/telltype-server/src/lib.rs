//! Telltype Server: wiring the configuration, credentials, speech engine, upstreams and supervised
//! `llama-server` processes into one running HTTP(S) server with graceful shutdown.

mod cli;
pub mod conn;
mod run;

pub use cli::{Command, KeysAction, parse_args, usage};
pub use run::{Loader, ServerError, credentials, fingerprint, keys, serve, sherpa_loader};
