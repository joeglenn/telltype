//! Named bearer keys: the single `auth.key_file` (named `default`) and one `<name>.key` file per
//! person in `auth.keys_dir`, each optionally limited to roles. The directory is read again when
//! it changes (and on SIGHUP), so a key added or revoked takes effect without a restart.
//!
//! A key file is mode 0600 in a directory of mode 0700. Its first line is the key; an optional
//! line `roles = speech, cleanup` limits it. Names appear in logs; keys never do, and only their
//! SHA-256 digests stay in memory, compared in constant time against every key.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::time::SystemTime;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::credentials::{CredentialError, MIN_KEY_CHARS, check_private, io, write_private};

/// The roles a key can be limited to.
pub const ROLES: [&str; 3] = ["speech", "cleanup", "commands"];

/// Who a request's key belongs to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Identity {
    pub name: String,
    /// `None`: every role.
    pub roles: Option<Vec<String>>,
}

impl Identity {
    #[must_use]
    pub fn may(&self, role: &str) -> bool {
        self.roles
            .as_ref()
            .is_none_or(|roles| roles.iter().any(|r| r == role))
    }
}

struct Entry {
    identity: Identity,
    digest: [u8; 32],
}

/// One key file's contents.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyInfo {
    pub name: String,
    pub roles: Option<Vec<String>>,
    /// Why the file is not used (permissions, too short ...); `None` when it is valid.
    pub problem: Option<String>,
}

/// What a reload found.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reloaded {
    pub keys: usize,
    /// Names of key files left out, with the reason (never the key).
    pub skipped: Vec<String>,
}

type Stamp = Vec<(PathBuf, Option<SystemTime>, u64)>;

/// `(name, key, roles)` for [`KeyStore::fixed`].
pub type FixedKey<'a> = (&'a str, &'a [u8], Option<&'a [&'a str]>);

/// The keys the server accepts.
pub struct KeyStore {
    key_file: Option<PathBuf>,
    keys_dir: Option<PathBuf>,
    entries: RwLock<Arc<Vec<Entry>>>,
    stamp: Mutex<Stamp>,
}

impl std::fmt::Debug for KeyStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("KeyStore").field("names", &self.names()).finish()
    }
}

fn digest(key: &[u8]) -> [u8; 32] {
    Sha256::digest(key).into()
}

impl KeyStore {
    /// Read every key at start. Any unusable file is an error here (exit code 2).
    pub fn load(key_file: Option<&Path>, keys_dir: Option<&Path>) -> Result<Self, CredentialError> {
        let store = Self {
            key_file: key_file.map(Path::to_path_buf),
            keys_dir: keys_dir.map(Path::to_path_buf),
            entries: RwLock::new(Arc::new(Vec::new())),
            stamp: Mutex::new(Vec::new()),
        };
        if let Some(dir) = keys_dir {
            check_private(dir)?;
        }
        let (entries, problems) = store.read_all()?;
        if let Some(error) = problems.into_iter().next() {
            return Err(error);
        }
        store.install(entries);
        Ok(store)
    }

    /// A fixed set of keys (tests and embedders): `(name, key, roles)`.
    #[must_use]
    pub fn fixed(keys: &[FixedKey<'_>]) -> Self {
        let entries = keys
            .iter()
            .map(|(name, key, roles)| Entry {
                identity: Identity {
                    name: (*name).to_owned(),
                    roles: roles.map(|r| r.iter().map(|s| (*s).to_owned()).collect()),
                },
                digest: digest(key),
            })
            .collect();
        Self {
            key_file: None,
            keys_dir: None,
            entries: RwLock::new(Arc::new(entries)),
            stamp: Mutex::new(Vec::new()),
        }
    }

    /// The identity whose key is `token`, comparing against every key in constant time.
    #[must_use]
    pub fn check(&self, token: &[u8]) -> Option<Identity> {
        let wanted = digest(token);
        let entries = self.entries.read().map(|e| Arc::clone(&e)).ok()?;
        let mut found = None;
        for entry in entries.iter() {
            let same = bool::from(entry.digest.ct_eq(&wanted));
            if same && found.is_none() {
                found = Some(entry.identity.clone());
            }
        }
        found
    }

    #[must_use]
    pub fn names(&self) -> Vec<String> {
        self.entries
            .read()
            .map(|e| e.iter().map(|k| k.identity.name.clone()).collect())
            .unwrap_or_default()
    }

    /// Read the keys again. Unusable files in the directory are left out (their keys stop
    /// working), the others replace the current set at once.
    pub fn reload(&self) -> Result<Reloaded, CredentialError> {
        let (entries, problems) = self.read_all()?;
        let keys = entries.len();
        self.install(entries);
        Ok(Reloaded {
            keys,
            skipped: problems.iter().map(ToString::to_string).collect(),
        })
    }

    /// [`Self::reload`] when a key file or the directory changed since the last read.
    pub fn reload_if_changed(&self) -> Option<Result<Reloaded, CredentialError>> {
        let now = self.current_stamp();
        let changed = self.stamp.lock().map(|s| *s != now).unwrap_or(true);
        changed.then(|| self.reload())
    }

    fn install(&self, entries: Vec<Entry>) {
        let stamp = self.current_stamp();
        if let Ok(mut current) = self.entries.write() {
            *current = Arc::new(entries);
        }
        if let Ok(mut current) = self.stamp.lock() {
            *current = stamp;
        }
    }

    fn current_stamp(&self) -> Stamp {
        let mut paths: Vec<PathBuf> = self.key_file.iter().cloned().collect();
        if let Some(dir) = &self.keys_dir {
            paths.push(dir.clone());
            paths.extend(key_files(dir).unwrap_or_default().into_iter().map(|(_, p)| p));
        }
        paths
            .into_iter()
            .map(|path| {
                let meta = std::fs::metadata(&path).ok();
                let modified = meta.as_ref().and_then(|m| m.modified().ok());
                let len = meta.map_or(0, |m| m.len());
                (path, modified, len)
            })
            .collect()
    }

    /// All keys, and the problems of files that could not be used. The single key file failing
    /// is always an error.
    fn read_all(&self) -> Result<(Vec<Entry>, Vec<CredentialError>), CredentialError> {
        let mut entries = Vec::new();
        let mut problems = Vec::new();
        if let Some(path) = &self.key_file {
            let (key, roles) = read_key_file(path)?;
            entries.push(Entry {
                identity: Identity {
                    name: "default".to_owned(),
                    roles,
                },
                digest: digest(&key),
            });
        }
        if let Some(dir) = &self.keys_dir {
            for (name, path) in key_files(dir)? {
                match read_key_file(&path) {
                    Ok((key, roles)) => entries.push(Entry {
                        identity: Identity { name, roles },
                        digest: digest(&key),
                    }),
                    Err(error) => problems.push(error),
                }
            }
        }
        Ok((entries, problems))
    }
}

/// `<name>.key` files in `dir`, sorted by name.
fn key_files(dir: &Path) -> Result<Vec<(String, PathBuf)>, CredentialError> {
    let mut found = Vec::new();
    let read = std::fs::read_dir(dir).map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            CredentialError::Missing(dir.to_path_buf())
        } else {
            io(dir)(e)
        }
    })?;
    for entry in read.flatten() {
        let path = entry.path();
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| n.strip_suffix(".key"))
            .filter(|n| valid_name(n));
        if let Some(name) = name {
            found.push((name.to_owned(), path));
        }
    }
    found.sort();
    Ok(found)
}

/// 1 to 64 of `A-Za-z0-9._-`, not starting with a dot.
#[must_use]
pub fn valid_name(name: &str) -> bool {
    (1..=64).contains(&name.len())
        && !name.starts_with('.')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

fn parse_roles(path: &Path, text: &str) -> Result<Vec<String>, CredentialError> {
    let roles: Vec<String> = text
        .split(',')
        .map(|r| r.trim().to_owned())
        .filter(|r| !r.is_empty())
        .collect();
    if roles.is_empty() {
        return Err(bad(path, "roles is empty (remove the line for every role)"));
    }
    if let Some(unknown) = roles.iter().find(|r| !ROLES.contains(&r.as_str())) {
        return Err(bad(
            path,
            &format!("unknown role {unknown:?} (speech, cleanup, commands)"),
        ));
    }
    Ok(roles)
}

fn bad(path: &Path, reason: &str) -> CredentialError {
    CredentialError::BadKeyFile {
        path: path.to_path_buf(),
        reason: reason.to_owned(),
    }
}

/// The key and its roles from a key file (mode 0600, the key at least 32 characters).
pub fn read_key_file(path: &Path) -> Result<(Vec<u8>, Option<Vec<String>>), CredentialError> {
    check_private(path)?;
    let text = std::fs::read_to_string(path).map_err(io(path))?;
    let (mut key, mut roles) = (None, None);
    for line in text.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some(rest) = line.strip_prefix("roles").map(str::trim_start)
            && let Some(list) = rest.strip_prefix('=').or_else(|| rest.strip_prefix(':'))
        {
            roles = Some(parse_roles(path, list)?);
        } else if key.is_none() {
            key = Some(line.to_owned());
        } else {
            return Err(bad(path, "holds more than one key"));
        }
    }
    let key = key.ok_or_else(|| CredentialError::ShortKey(path.to_path_buf()))?;
    if key.chars().count() < MIN_KEY_CHARS {
        return Err(CredentialError::ShortKey(path.to_path_buf()));
    }
    Ok((key.into_bytes(), roles))
}

/// `keys add`: a new random key for `name` in `dir` (created with mode 0700). Refuses an existing
/// name. Returns the file's path; the key is only in the file.
pub fn add_key(dir: &Path, name: &str, roles: Option<&[String]>) -> Result<PathBuf, CredentialError> {
    if !valid_name(name) {
        return Err(CredentialError::BadName(name.to_owned()));
    }
    let path = dir.join(format!("{name}.key"));
    if let Some(roles) = roles {
        parse_roles(&path, &roles.join(","))?;
    }
    if path.exists() {
        return Err(CredentialError::Exists(path));
    }
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).map_err(|_| CredentialError::Random)?;
    let mut text = format!("{}\n", URL_SAFE_NO_PAD.encode(random));
    if let Some(roles) = roles {
        text = format!("{text}roles = {}\n", roles.join(", "));
    }
    write_private(&path, text.as_bytes())?;
    Ok(path)
}

/// `keys list`: names and roles, never keys.
pub fn list_keys(dir: &Path) -> Result<Vec<KeyInfo>, CredentialError> {
    Ok(key_files(dir)?
        .into_iter()
        .map(|(name, path)| match read_key_file(&path) {
            Ok((_, roles)) => KeyInfo {
                name,
                roles,
                problem: None,
            },
            Err(error) => KeyInfo {
                name,
                roles: None,
                problem: Some(error.to_string()),
            },
        })
        .collect())
}

/// `keys revoke`: delete the key file. A running server stops accepting the key within seconds.
pub fn revoke_key(dir: &Path, name: &str) -> Result<PathBuf, CredentialError> {
    if !valid_name(name) {
        return Err(CredentialError::BadName(name.to_owned()));
    }
    let path = dir.join(format!("{name}.key"));
    std::fs::remove_file(&path).map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            CredentialError::Missing(path.clone())
        } else {
            io(&path)(e)
        }
    })?;
    Ok(path)
}

#[cfg(test)]
mod tests;
