//! Runs `llama-server` processes for Telltype Server: start, wait for health, restart with
//! exponential back-off when one exits or stops answering, and stop them on shutdown (SIGTERM, then
//! SIGKILL after a deadline). Also writes the presets file for `llama-server`'s router mode.

mod presets;
mod process;

pub use presets::{PresetError, Presets, build_presets, write_presets};
pub use process::{ProcessSpec, Supervisor, SupervisorStatus};
