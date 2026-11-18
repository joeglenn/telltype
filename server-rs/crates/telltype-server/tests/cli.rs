#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! The binary's commands as a user runs them: exit codes, output, and that the key is never printed.

use std::path::{Path, PathBuf};
use std::process::{Command, Output};

fn binary() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_telltype-server"));
    command.env_remove("TELLTYPE_CONFIG").env("TELLTYPE_LOG", "info");
    command
}

fn run(args: &[&str]) -> Output {
    binary().args(args).output().unwrap()
}

fn text(output: &Output) -> String {
    format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    )
}

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telltype-cli-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn config(dir: &Path, model_dir: &str) -> String {
    let path = dir.join("server.toml");
    std::fs::write(
        &path,
        format!(
            "[server]\nlisten = \"127.0.0.1:0\"\n[tls]\nsubject_alt_names = [\"localhost\"]\n[auth]\nkey_file = \"gate.key\"\n\
             [speech]\nmodel_dir = \"{model_dir}\"\n[text.cleanup]\nmodel = \"c\"\nurl = \"http://127.0.0.1:9\"\n"
        ),
    )
    .unwrap();
    path.display().to_string()
}

#[test]
fn help_version_and_bad_arguments() {
    let help = run(&["--help"]);
    assert!(help.status.success() && text(&help).contains("check-config"));
    let version = run(&["--version"]);
    assert_eq!(
        text(&version).trim(),
        format!("telltype-server {}", env!("CARGO_PKG_VERSION"))
    );
    let bad = run(&["--bogus"]);
    assert_eq!(bad.status.code(), Some(2));
    assert!(text(&bad).contains("unknown argument"));
    assert_eq!(
        run(&[]).status.code(),
        Some(2),
        "a configuration file is required"
    );
}

#[test]
fn check_config_names_the_bad_key_with_exit_code_2() {
    let dir = scratch("check");
    let good = config(&dir, "/nonexistent");
    let ok = run(&["check-config", "--config", &good]);
    assert!(ok.status.success(), "{}", text(&ok));
    std::fs::write(dir.join("bad.toml"), "[server]\nlisten = \"nope\"\n").unwrap();
    let bad = run(&["check-config", "--config", dir.join("bad.toml").to_str().unwrap()]);
    assert_eq!(bad.status.code(), Some(2));
    assert!(text(&bad).contains("server.listen"), "{}", text(&bad));
    let via_env = binary()
        .args(["check-config"])
        .env("TELLTYPE_CONFIG", &good)
        .output()
        .unwrap();
    assert!(via_env.status.success());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn credentials_and_fingerprint_never_print_the_key() {
    let dir = scratch("cred");
    let path = config(&dir, "/nonexistent");
    let first = run(&["credentials", "--config", &path]);
    assert!(first.status.success(), "{}", text(&first));
    let key = std::fs::read_to_string(dir.join("gate.key")).unwrap();
    let out = text(&first);
    assert!(
        out.contains("key: created") && out.contains("certificate: created") && out.contains("sha256/"),
        "{out}"
    );
    let rotated = run(&["credentials", "--config", &path, "--rotate-key"]);
    let new_key = std::fs::read_to_string(dir.join("gate.key")).unwrap();
    assert_ne!(key, new_key);
    let prints = run(&["fingerprint", "--config", &path]);
    assert!(prints.status.success() && text(&prints).contains("cert sha256 (hex)"));
    for output in [&first, &rotated, &prints] {
        assert!(!text(output).contains(key.trim()) && !text(output).contains(new_key.trim()));
    }
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn serve_exits_1_when_the_model_is_missing() {
    let dir = scratch("serve");
    let path = config(&dir, "/nonexistent/parakeet");
    run(&["credentials", "--config", &path]);
    let output = run(&["serve", "--config", &path]);
    assert_eq!(output.status.code(), Some(1), "{}", text(&output));
    let out = text(&output);
    assert!(
        out.contains("\"event\":\"listening\"") && out.contains("encoder.int8.onnx"),
        "{out}"
    );
    assert!(
        out.contains("\"spki_sha256\":\"sha256/"),
        "the pin is logged at start: {out}"
    );
    std::fs::remove_dir_all(&dir).unwrap();
}
