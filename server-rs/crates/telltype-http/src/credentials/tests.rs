use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

use super::{
    CredentialError, Outcome, ensure_certificate, ensure_key, fingerprints, load_key, require_certificate,
};

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telltype-cred-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn mode(path: &Path) -> u32 {
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

#[test]
fn key_is_created_private_kept_and_rotated() {
    let dir = scratch("key");
    let key = dir.join("conf/gate.key");
    assert_eq!(ensure_key(&key, false).unwrap(), Outcome::Created);
    assert_eq!((mode(&key), mode(&dir.join("conf"))), (0o600, 0o700));
    let first = load_key(&key).unwrap();
    assert_eq!(first.len(), 43);
    assert_eq!(ensure_key(&key, false).unwrap(), Outcome::Kept);
    assert_eq!(load_key(&key).unwrap(), first);
    assert_eq!(ensure_key(&key, true).unwrap(), Outcome::Rotated);
    assert_ne!(load_key(&key).unwrap(), first);
    assert_eq!(Outcome::Rotated.to_string(), "rotated");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn key_loading_refuses_readable_short_and_missing_files() {
    let dir = scratch("refuse");
    std::fs::create_dir_all(&dir).unwrap();
    let key = dir.join("gate.key");
    std::fs::write(&key, format!("{}\n", "x".repeat(40))).unwrap();
    std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o644)).unwrap();
    let err = load_key(&key).unwrap_err();
    assert!(
        matches!(err, CredentialError::Permissions { mode: 0o644, .. }),
        "{err}"
    );
    assert!(err.to_string().contains("chmod 600"));
    std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o600)).unwrap();
    assert_eq!(load_key(&key).unwrap(), b"x".repeat(40));
    std::fs::write(&key, "short").unwrap();
    assert!(matches!(load_key(&key), Err(CredentialError::ShortKey(_))));
    assert!(matches!(
        load_key(&dir.join("missing.key")),
        Err(CredentialError::Missing(_))
    ));
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn certificate_is_generated_with_names_and_fingerprints_match_openssl() {
    let dir = scratch("cert");
    let (cert, key) = (dir.join("tls/cert.pem"), dir.join("tls/key.pem"));
    let names = vec![
        "localhost".to_owned(),
        "server.local".to_owned(),
        "127.0.0.1".to_owned(),
    ];
    assert_eq!(
        ensure_certificate(&cert, &key, "telltype-server", &names, false).unwrap(),
        Outcome::Created
    );
    assert_eq!(
        (mode(&cert), mode(&key), mode(&dir.join("tls"))),
        (0o600, 0o600, 0o700)
    );
    require_certificate(&cert, &key).unwrap();
    let before = std::fs::read(&cert).unwrap();
    assert_eq!(
        ensure_certificate(&cert, &key, "telltype-server", &names, false).unwrap(),
        Outcome::Kept
    );
    assert_eq!(std::fs::read(&cert).unwrap(), before);
    let prints = fingerprints(&cert).unwrap();
    assert_eq!(prints.cert_sha256_hex.len(), 64);

    let text = run(
        "openssl",
        &["x509", "-in", cert.to_str().unwrap(), "-noout", "-text"],
    );
    for expected in [
        "DNS:localhost",
        "DNS:server.local",
        "IP Address:127.0.0.1",
        "prime256v1",
        "CN=telltype-server",
    ] {
        assert!(
            text.replace("CN = ", "CN=").contains(expected),
            "{expected} in {text}"
        );
    }
    let openssl_hex = run(
        "openssl",
        &[
            "x509",
            "-in",
            cert.to_str().unwrap(),
            "-noout",
            "-fingerprint",
            "-sha256",
        ],
    );
    let openssl_hex: String = openssl_hex
        .split('=')
        .nth(1)
        .unwrap()
        .trim()
        .replace(':', "")
        .to_lowercase();
    assert_eq!(prints.cert_sha256_hex, openssl_hex);
    let spki = Command::new("sh")
        .arg("-c")
        .arg(format!(
            "openssl x509 -in {} -noout -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64",
            cert.display()
        ))
        .output()
        .unwrap();
    assert_eq!(
        prints.spki_sha256_base64,
        String::from_utf8(spki.stdout).unwrap().trim()
    );

    assert_eq!(
        ensure_certificate(&cert, &key, "telltype-server", &names, true).unwrap(),
        Outcome::Rotated
    );
    assert_ne!(fingerprints(&cert).unwrap(), prints);
    std::fs::remove_file(&key).unwrap();
    assert!(matches!(
        ensure_certificate(&cert, &key, "x", &names, false),
        Err(CredentialError::HalfPair { .. })
    ));
    assert!(matches!(
        require_certificate(&dir.join("none.pem"), &key),
        Err(CredentialError::Missing(_))
    ));
    std::fs::write(dir.join("not.pem"), "hello").unwrap();
    assert!(matches!(
        fingerprints(&dir.join("not.pem")),
        Err(CredentialError::NotPem(_))
    ));
    assert!(matches!(
        ensure_certificate(&cert, &key, "x", &["b\u{fc}cher.example".to_owned()], true),
        Err(CredentialError::Generate(_))
    ));
    std::fs::remove_dir_all(&dir).unwrap();
}

fn run(program: &str, args: &[&str]) -> String {
    let out = Command::new(program).args(args).output().unwrap();
    assert!(out.status.success(), "{program} {args:?}");
    String::from_utf8(out.stdout).unwrap()
}
