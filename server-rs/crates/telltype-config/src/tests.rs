use std::path::Path;
use std::time::Duration;

use super::{Config, ConfigError, TlsMode};

const MINIMAL: &str = r#"
[auth]
key_file = "gate.key"

[speech]
model_dir = "~/models/parakeet"

[text.cleanup]
model = "gemma"
"#;

fn parse(text: &str) -> Result<Config, ConfigError> {
    Config::parse(text, Path::new("/etc/telltype"), Some(Path::new("/home/u")))
}

fn err_key(text: &str) -> String {
    parse(text).unwrap_err().key
}

#[test]
fn minimal_file_gets_documented_defaults() {
    let c = parse(MINIMAL).unwrap();
    assert_eq!(c.server.listen.to_string(), "0.0.0.0:8170");
    assert_eq!(c.server.max_upload_bytes, 25 * 1024 * 1024);
    assert_eq!(c.server.multipart_overhead_bytes, 1024 * 1024);
    assert_eq!(c.server.max_json_bytes, 1024 * 1024);
    assert_eq!(c.server.retry_after, Duration::from_secs(5));
    assert_eq!(c.server.health_timeout, Duration::from_secs(2));
    assert_eq!(c.tls.mode, TlsMode::SelfSigned);
    assert_eq!(c.tls.cert_file, Path::new("/etc/telltype/tls/cert.pem"));
    assert_eq!(
        c.auth.key_file.as_deref(),
        Some(Path::new("/etc/telltype/gate.key"))
    );
    assert_eq!(c.speech.model_dir, Path::new("/home/u/models/parakeet"));
    assert_eq!(c.speech.model_name, "parakeet-unified-en-0.6b-int8");
    assert_eq!(
        (c.speech.threads, c.speech.max_concurrent, c.speech.max_waiting),
        (8, 2, 4)
    );
    assert!(c.speech.hotwords.is_none());
    assert_eq!(c.cleanup.url, "http://127.0.0.1:8172");
    assert_eq!(c.cleanup.timeout, Duration::from_secs(60));
    assert_eq!(c.cleanup.max_in_flight, 4);
    assert_eq!(c.cleanup.health_path, "/health");
    assert!(c.commands.is_none());
    assert_eq!(c.upstreams().len(), 1);
}

#[test]
fn full_file_round_trips_every_key() {
    let text = r#"
[server]
listen = "127.0.0.1:18170"
max_upload_bytes = 1000
multipart_overhead_bytes = 10
max_json_bytes = 20
retry_after_s = 7
health_timeout_s = 0.5
shutdown_grace_s = 3

[tls]
mode = "files"
cert_file = "/c.pem"
key_file = "/k.pem"
subject_alt_names = ["server.local", "10.0.0.1"]
common_name = "x"

[auth]
open = true

[speech]
model_dir = "/m"
model_name = "p"
threads = 2
max_concurrent = 1
max_waiting = 0
max_audio_seconds = 60
recognition_timeout_s = 5
hotwords = true
hotwords_score = 1.5
max_hotwords = 10
vocab_dir = "/run/v"

[text.cleanup]
url = "http://127.0.0.1:18172/"
model = "c"
aliases = { cleanup = "cleanup" }
timeout_s = 1
max_in_flight = 1
health_path = "/healthz"

[text.commands]
url = "http://127.0.0.1:18173"
model = "q"
aliases = { assistant = "qwen" }

[text.commands.supervise]
binary = "/bin/llama-server"
args = ["--n-gpu-layers", "999", "--model", "~/m.gguf"]
restart_min_s = 0.5
restart_max_s = 2
stop_timeout_s = 3

[text.commands.supervise.router]
model_dirs = ["/models"]
models_ini = "/models.ini"
presets_file = "state/presets.ini"
models_max = 1
defaults = { ctx-size = "8192" }
"#;
    let c = parse(text).unwrap();
    assert_eq!(c.server.listen.port(), 18170);
    assert_eq!(c.server.health_timeout, Duration::from_millis(500));
    assert_eq!(c.tls.mode, TlsMode::Files);
    assert_eq!(c.tls.subject_alt_names, ["server.local", "10.0.0.1"]);
    assert!(c.auth.key_file.is_none());
    let hot = c.speech.hotwords.as_ref().unwrap();
    assert_eq!((hot.score, hot.max_terms), (1.5, 10));
    assert_eq!(c.cleanup.url, "http://127.0.0.1:18172");
    let commands = c.commands.as_ref().unwrap();
    assert_eq!(commands.aliases["assistant"], "qwen");
    let sup = commands.supervise.as_ref().unwrap();
    assert_eq!(sup.restart_min, Duration::from_millis(500));
    assert_eq!(sup.args, ["--n-gpu-layers", "999", "--model", "/home/u/m.gguf"]);
    let router = sup.router.as_ref().unwrap();
    assert_eq!(router.presets_file, Path::new("/etc/telltype/state/presets.ini"));
    assert_eq!(router.defaults["ctx-size"], "8192");
    assert_eq!(c.upstreams().len(), 2);
}

#[test]
fn errors_name_the_key() {
    let cases: &[(&str, &str)] = &[
        ("[server]\nlisten = \"nope\"\n", "server.listen"),
        ("[server]\nretry_after_s = 0\n", "server.retry_after_s"),
        ("[server]\nhealth_timeout_s = -1\n", "server.health_timeout_s"),
        ("[server]\nmax_json_bytes = 0\n", "server.max_json_bytes"),
        ("[tls]\nmode = \"maybe\"\n", "tls.mode"),
        ("[tls]\nmode = \"off\"\n", "tls.allow_plain_http"),
        ("[tls]\nsubject_alt_names = []\n", "tls.subject_alt_names"),
        ("[tls]\ncommon_name = \" \"\n", "tls.common_name"),
    ];
    for (extra, key) in cases {
        assert_eq!(err_key(&format!("{extra}{MINIMAL}")), *key, "{extra}");
    }
}

#[test]
fn section_errors_name_the_key() {
    let base_speech = "[speech]\nmodel_dir = \"/m\"\n";
    let base_auth = "[auth]\nkey_file = \"k\"\n";
    let cleanup = "[text.cleanup]\nmodel = \"c\"\n";
    let cases: Vec<(String, &str)> = vec![
        (format!("{base_speech}{cleanup}"), "auth.key_file"),
        (
            format!("[auth]\nkey_file = \"k\"\nopen = true\n{base_speech}{cleanup}"),
            "auth.open",
        ),
        (format!("{base_auth}{cleanup}"), "speech"),
        (format!("{base_auth}[speech]\n{cleanup}"), "speech.model_dir"),
        (format!("{base_auth}{base_speech}"), "text"),
        (
            format!("{base_auth}{base_speech}[text.commands]\nmodel = \"q\"\n"),
            "text.cleanup",
        ),
        (
            format!("{base_auth}{base_speech}[text.cleanup]\n"),
            "text.cleanup.model",
        ),
        (
            format!("{base_auth}{base_speech}[text.cleanup]\nmodel = \"c\"\nurl = \"ftp://x\"\n"),
            "text.cleanup.url",
        ),
        (
            format!("{base_auth}{base_speech}[text.cleanup]\nmodel = \"c\"\nurl = \"https://x\"\n"),
            "text.cleanup.url",
        ),
        (
            format!("{base_auth}{base_speech}[text.cleanup]\nmodel = \"c\"\nhealth_path = \"health\"\n"),
            "text.cleanup.health_path",
        ),
        (
            format!("{base_auth}{base_speech}[text.cleanup]\nmodel = \"c\"\naliases = {{ x = \"\" }}\n"),
            "text.cleanup.aliases.x",
        ),
        (
            format!("{base_auth}{base_speech}hotwords = true\n{cleanup}"),
            "speech.vocab_dir",
        ),
        (
            format!("{base_auth}{base_speech}threads = 0\n{cleanup}"),
            "speech.threads",
        ),
        (
            format!("{base_auth}{base_speech}max_concurrent = 0\n{cleanup}"),
            "speech.max_concurrent",
        ),
        (
            format!("{base_auth}{base_speech}max_audio_seconds = 0\n{cleanup}"),
            "speech.max_audio_seconds",
        ),
        (
            format!("{base_auth}{base_speech}model_name = \"\"\n{cleanup}"),
            "speech.model_name",
        ),
        (
            format!(
                "{base_auth}{base_speech}hotwords = true\nvocab_dir = \"/v\"\nhotwords_score = 0\n{cleanup}"
            ),
            "speech.hotwords_score",
        ),
        (
            format!(
                "{base_auth}{base_speech}[text.cleanup]\nmodel = \"c\"\naliases = {{ a = \"x\" }}\n\
                 [text.commands]\nmodel = \"q\"\naliases = {{ a = \"y\" }}\n"
            ),
            "text.commands.aliases.a",
        ),
    ];
    for (text, key) in cases {
        assert_eq!(err_key(&text), key, "{text}");
    }
}

#[test]
fn supervise_errors_name_the_key() {
    let head = "[auth]\nopen = true\n[speech]\nmodel_dir = \"/m\"\n[text.cleanup]\nmodel = \"c\"\n";
    let cases: &[(&str, &str)] = &[
        ("[text.cleanup.supervise]\n", "text.cleanup.supervise.binary"),
        (
            "[text.cleanup.supervise]\nbinary = \"/b\"\nargs = [\"--port\", \"1\"]\n",
            "text.cleanup.supervise.args",
        ),
        (
            "[text.cleanup.supervise]\nbinary = \"/b\"\nrestart_min_s = 5\nrestart_max_s = 1\n",
            "text.cleanup.supervise.restart_max_s",
        ),
        (
            "[text.cleanup.supervise]\nbinary = \"/b\"\n[text.cleanup.supervise.router]\n",
            "text.cleanup.supervise.router",
        ),
        (
            "[text.cleanup.supervise]\nbinary = \"/b\"\n[text.cleanup.supervise.router]\nmodel_dirs = [\"/m\"]\n",
            "text.cleanup.supervise.router.presets_file",
        ),
        (
            "[text.cleanup.supervise]\nbinary = \"/b\"\n[text.cleanup.supervise.router]\nmodel_dirs = [\"/m\"]\n\
             presets_file = \"/p\"\ndefaults = { \"a=b\" = \"1\" }\n",
            "text.cleanup.supervise.router.defaults",
        ),
    ];
    for (extra, key) in cases {
        assert_eq!(err_key(&format!("{head}{extra}")), *key, "{extra}");
    }
    let remote = head.replace("model = \"c\"", "model = \"c\"\nurl = \"http://10.0.0.2:1\"");
    assert_eq!(
        err_key(&format!("{remote}[text.cleanup.supervise]\nbinary = \"/b\"\n")),
        "text.cleanup.url"
    );
}

#[test]
fn unknown_keys_and_bad_toml_are_errors_that_say_where() {
    let err = parse(&format!("{MINIMAL}\n[speech.extra]\n")).unwrap_err();
    assert!(err.message.contains("invalid TOML"), "{err}");
    let err = parse(&format!("[server]\nlisen = \"x\"\n{MINIMAL}")).unwrap_err();
    assert!(err.message.contains("lisen"), "{err}");
    assert!(err.to_string().starts_with("invalid TOML"), "{err}");
    let err = parse("this is = = not toml").unwrap_err();
    assert!(err.message.contains("line 1"), "{err}");
}

#[test]
fn tilde_without_home_and_empty_paths_are_errors() {
    let err = Config::parse(MINIMAL, Path::new("/"), None).unwrap_err();
    assert_eq!(err.key, "speech.model_dir");
    assert_eq!(
        err.to_string(),
        "speech.model_dir: starts with ~/ but HOME is not set"
    );
    assert_eq!(err_key(&MINIMAL.replace("\"gate.key\"", "\"\"")), "auth.key_file");
}

#[test]
fn load_reads_a_file_and_reports_a_missing_one() {
    let dir = std::env::temp_dir().join(format!("telltype-config-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let file = dir.join("server.toml");
    std::fs::write(&file, MINIMAL.replace("~/models", "/models")).unwrap();
    let c = Config::load(&file).unwrap();
    assert_eq!(c.auth.key_file.unwrap(), dir.join("gate.key"));
    std::fs::remove_file(&file).unwrap();
    std::fs::remove_dir(&dir).unwrap();
    let err = Config::load(&file).unwrap_err();
    assert!(err.message.starts_with("cannot read"), "{err}");
}
