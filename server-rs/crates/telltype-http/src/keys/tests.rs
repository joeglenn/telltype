#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;

use super::{KeyStore, add_key, list_keys, read_key_file, revoke_key, valid_name};
use crate::credentials::CredentialError;

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("telltype-keys-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn key_of(path: &std::path::Path) -> Vec<u8> {
    read_key_file(path).unwrap().0
}

#[test]
fn add_list_revoke_and_reload_without_restart() {
    let base = scratch("cycle");
    let dir = base.join("keys");
    let alice = add_key(&dir, "alice", None).unwrap();
    let roles = vec!["speech".to_owned(), "cleanup".to_owned()];
    let bob = add_key(&dir, "bob", Some(&roles)).unwrap();
    assert_eq!(
        std::fs::metadata(&dir).unwrap().permissions().mode() & 0o777,
        0o700
    );
    assert_eq!(
        std::fs::metadata(&alice).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert!(matches!(
        add_key(&dir, "alice", None),
        Err(CredentialError::Exists(_))
    ));
    let listed = list_keys(&dir).unwrap();
    assert_eq!(listed.len(), 2);
    assert_eq!(
        (listed[0].name.as_str(), listed[0].roles.as_ref()),
        ("alice", None)
    );
    assert_eq!(listed[1].roles.as_deref(), Some(&roles[..]));
    assert!(!format!("{listed:?}").contains(&String::from_utf8(key_of(&alice)).unwrap()));

    let store = KeyStore::load(None, Some(&dir)).unwrap();
    assert_eq!(store.names(), ["alice", "bob"]);
    let who = store.check(&key_of(&bob)).unwrap();
    assert_eq!(who.name, "bob");
    assert!(who.may("speech") && !who.may("commands"));
    assert!(store.check(&key_of(&alice)).unwrap().may("commands"));
    assert!(store.check(b"wrong").is_none());
    assert!(!format!("{store:?}").contains(&String::from_utf8(key_of(&alice)).unwrap()));
    assert!(store.reload_if_changed().is_none(), "nothing changed");

    let alice_key = key_of(&alice);
    revoke_key(&dir, "alice").unwrap();
    let reloaded = store.reload_if_changed().unwrap().unwrap();
    assert_eq!(reloaded.keys, 1);
    assert!(store.check(&alice_key).is_none(), "revoked without a restart");
    assert!(matches!(
        revoke_key(&dir, "alice"),
        Err(CredentialError::Missing(_))
    ));

    // A file others can read is left out on reload (its key stops working), the rest stay.
    let carol = add_key(&dir, "carol", None).unwrap();
    std::fs::set_permissions(&carol, std::fs::Permissions::from_mode(0o644)).unwrap();
    let reloaded = store.reload().unwrap();
    assert_eq!((reloaded.keys, reloaded.skipped.len()), (1, 1));
    assert!(store.check(&key_of_unchecked(&carol)).is_none());
    // ... but refuses to start.
    assert!(matches!(
        KeyStore::load(None, Some(&dir)),
        Err(CredentialError::Permissions { .. })
    ));
    let listed = list_keys(&dir).unwrap();
    assert!(listed.iter().any(|k| k.name == "carol" && k.problem.is_some()));
    std::fs::remove_dir_all(&base).unwrap();
}

fn key_of_unchecked(path: &std::path::Path) -> Vec<u8> {
    std::fs::read_to_string(path)
        .unwrap()
        .lines()
        .next()
        .unwrap()
        .as_bytes()
        .to_vec()
}

#[test]
fn the_single_key_file_is_named_default_and_files_are_checked() {
    let dir = scratch("single");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).unwrap();
    let file = dir.join("gate.key");
    crate::credentials::ensure_key(&file, false).unwrap();
    let store = KeyStore::load(Some(&file), None).unwrap();
    assert_eq!(store.check(&key_of(&file)).unwrap().name, "default");

    let write = |name: &str, text: &str| {
        let path = dir.join(name);
        crate::credentials::write_private(&path, text.as_bytes()).unwrap();
        path
    };
    let long = "k".repeat(40);
    assert!(matches!(
        read_key_file(&write("a", "short\n")),
        Err(CredentialError::ShortKey(_))
    ));
    assert!(matches!(
        read_key_file(&write("b", "# only a comment\n")),
        Err(CredentialError::ShortKey(_))
    ));
    let two = write("c", &format!("{long}\n{long}\n"));
    assert!(matches!(
        read_key_file(&two),
        Err(CredentialError::BadKeyFile { .. })
    ));
    let unknown = write("d", &format!("{long}\nroles = speech, admin\n"));
    assert!(read_key_file(&unknown).unwrap_err().to_string().contains("admin"));
    let empty = write("e", &format!("{long}\nroles =\n"));
    assert!(read_key_file(&empty).is_err());
    let colon = write("f", &format!("# comment\n\n{long}\nroles: commands\n"));
    assert_eq!(
        read_key_file(&colon).unwrap().1,
        Some(vec!["commands".to_owned()])
    );
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn names_and_bad_arguments() {
    for good in ["alice", "a", "phone-2", "j.b_g", &"x".repeat(64)] {
        assert!(valid_name(good), "{good}");
    }
    for bad in ["", ".hidden", "a/b", "../x", "sp ace", "ü", &"x".repeat(65)] {
        assert!(!valid_name(bad), "{bad}");
    }
    let dir = scratch("names");
    assert!(matches!(
        add_key(&dir, "../evil", None),
        Err(CredentialError::BadName(_))
    ));
    assert!(matches!(
        revoke_key(&dir, "a/b"),
        Err(CredentialError::BadName(_))
    ));
    let roles = vec!["root".to_owned()];
    assert!(add_key(&dir, "x", Some(&roles)).is_err());
    assert!(matches!(list_keys(&dir), Err(CredentialError::Missing(_))));
    assert!(matches!(
        KeyStore::load(None, Some(&dir)),
        Err(CredentialError::Missing(_))
    ));
    let fixed = KeyStore::fixed(&[("t", b"secret-secret-secret-secret-secret", Some(&["speech"]))]);
    assert!(
        !fixed
            .check(b"secret-secret-secret-secret-secret")
            .unwrap()
            .may("cleanup")
    );
}
