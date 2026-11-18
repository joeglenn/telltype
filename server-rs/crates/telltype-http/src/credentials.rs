//! The bearer key and the TLS certificate: loading with permission checks, creating and rotating
//! them, and the fingerprints a client pins.
//!
//! Both secrets are files of mode 0600 in directories of mode 0700. They are never printed or
//! logged; only the certificate's fingerprints are.

use std::fmt::Write as _;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};

/// Keys shorter than this are refused.
pub const MIN_KEY_CHARS: usize = 32;

#[derive(Debug, thiserror::Error)]
pub enum CredentialError {
    #[error("{0} does not exist")]
    Missing(PathBuf),
    #[error("{path} must not be readable by group or others (mode {mode:o}); run chmod 600")]
    Permissions { path: PathBuf, mode: u32 },
    #[error("the key in {0} is shorter than {MIN_KEY_CHARS} characters")]
    ShortKey(PathBuf),
    #[error("{path}: {source}")]
    Io { path: PathBuf, source: std::io::Error },
    #[error("only one of {cert} and {key} exists; restore the other or remove both to generate a new pair")]
    HalfPair { cert: PathBuf, key: PathBuf },
    #[error("cannot generate the certificate: {0}")]
    Generate(String),
    #[error("{0} holds no PEM certificate")]
    NotPem(PathBuf),
    #[error("the system random generator failed")]
    Random,
    #[error("{path}: {reason}")]
    BadKeyFile { path: PathBuf, reason: String },
    #[error("{0:?} is not a valid key name (1 to 64 of A-Z a-z 0-9 . _ -, not starting with .)")]
    BadName(String),
    #[error("{0} already exists; revoke it first or choose another name")]
    Exists(PathBuf),
}

/// What [`ensure_key`] and [`ensure_certificate`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Created,
    Kept,
    Rotated,
}

impl std::fmt::Display for Outcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Created => "created",
            Self::Kept => "kept",
            Self::Rotated => "rotated",
        })
    }
}

/// The two forms clients pin: the certificate's SHA-256 (hex) and its public key's (base64, as
/// `OkHttp`'s `CertificatePinner` takes it after `sha256/`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fingerprints {
    pub cert_sha256_hex: String,
    pub spki_sha256_base64: String,
}

pub(crate) fn io(path: &Path) -> impl FnOnce(std::io::Error) -> CredentialError + '_ {
    move |source| CredentialError::Io {
        path: path.to_path_buf(),
        source,
    }
}

pub(crate) fn check_private(path: &Path) -> Result<(), CredentialError> {
    let metadata = std::fs::metadata(path).map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            CredentialError::Missing(path.to_path_buf())
        } else {
            CredentialError::Io {
                path: path.to_path_buf(),
                source: e,
            }
        }
    })?;
    let mode = metadata.permissions().mode() & 0o777;
    if mode & 0o077 != 0 {
        return Err(CredentialError::Permissions {
            path: path.to_path_buf(),
            mode,
        });
    }
    Ok(())
}

/// Read the bearer key. Refuses files others could read and keys shorter than 32 characters.
pub fn load_key(path: &Path) -> Result<Vec<u8>, CredentialError> {
    check_private(path)?;
    let key = std::fs::read_to_string(path).map_err(io(path))?.trim().to_owned();
    if key.chars().count() < MIN_KEY_CHARS {
        return Err(CredentialError::ShortKey(path.to_path_buf()));
    }
    Ok(key.into_bytes())
}

/// Create the key file (32 random bytes, URL-safe base64) unless it exists; `rotate` replaces it.
pub fn ensure_key(path: &Path, rotate: bool) -> Result<Outcome, CredentialError> {
    let exists = path.exists();
    if exists && !rotate {
        load_key(path)?;
        return Ok(Outcome::Kept);
    }
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).map_err(|_| CredentialError::Random)?;
    write_private(path, format!("{}\n", URL_SAFE_NO_PAD.encode(random)).as_bytes())?;
    Ok(if exists {
        Outcome::Rotated
    } else {
        Outcome::Created
    })
}

/// Make sure a certificate and key exist at `cert` and `key`: kept when both exist, generated
/// (self-signed ECDSA P-256, valid ten years, the given names) when neither does or on `rotate`.
pub fn ensure_certificate(
    cert: &Path,
    key: &Path,
    common_name: &str,
    subject_alt_names: &[String],
    rotate: bool,
) -> Result<Outcome, CredentialError> {
    match (cert.exists(), key.exists(), rotate) {
        (true, true, false) => {
            check_private(key)?;
            Ok(Outcome::Kept)
        }
        (true, false, false) | (false, true, false) => Err(CredentialError::HalfPair {
            cert: cert.to_path_buf(),
            key: key.to_path_buf(),
        }),
        (had_cert, _, _) => {
            let (cert_pem, key_pem) = generate(common_name, subject_alt_names)?;
            write_private(key, key_pem.as_bytes())?;
            write_private(cert, cert_pem.as_bytes())?;
            Ok(if had_cert {
                Outcome::Rotated
            } else {
                Outcome::Created
            })
        }
    }
}

/// Require an existing pair (`tls.mode = "files"`).
pub fn require_certificate(cert: &Path, key: &Path) -> Result<(), CredentialError> {
    if !cert.is_file() {
        return Err(CredentialError::Missing(cert.to_path_buf()));
    }
    check_private(key)
}

fn generate(common_name: &str, names: &[String]) -> Result<(String, String), CredentialError> {
    let error = |e: rcgen::Error| CredentialError::Generate(e.to_string());
    let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(error)?;
    let mut params = rcgen::CertificateParams::new(names.to_vec()).map_err(error)?;
    params.distinguished_name = rcgen::DistinguishedName::new();
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, common_name);
    let year = current_year();
    params.not_before = rcgen::date_time_ymd(year - 1, 1, 1);
    params.not_after = rcgen::date_time_ymd(year + 10, 1, 1);
    let cert = params.self_signed(&key_pair).map_err(error)?;
    Ok((cert.pem(), key_pair.serialize_pem()))
}

fn current_year() -> i32 {
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    // Days since 1970 over the mean Gregorian year; exact enough for a validity start of 1 January.
    let years = seconds / 86_400 * 10_000 / 3_652_425;
    1970 + i32::try_from(years).unwrap_or(0)
}

/// Write a secret atomically with mode 0600, creating missing directories with mode 0700.
pub(crate) fn write_private(path: &Path, contents: &[u8]) -> Result<(), CredentialError> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty() && !p.exists()) {
        std::fs::create_dir_all(parent).map_err(io(parent))?;
        std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700)).map_err(io(parent))?;
    }
    let temporary = path.with_extension("tmp");
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&temporary)
        .map_err(io(&temporary))?;
    file.write_all(contents).map_err(io(&temporary))?;
    file.sync_all().map_err(io(&temporary))?;
    std::fs::rename(&temporary, path).map_err(io(path))
}

/// Fingerprints of the first certificate in a PEM file.
pub fn fingerprints(cert_file: &Path) -> Result<Fingerprints, CredentialError> {
    let pem = std::fs::read_to_string(cert_file).map_err(io(cert_file))?;
    let der = first_certificate(&pem).ok_or_else(|| CredentialError::NotPem(cert_file.to_path_buf()))?;
    let spki =
        der::subject_public_key_info(&der).ok_or_else(|| CredentialError::NotPem(cert_file.to_path_buf()))?;
    let hex = Sha256::digest(&der)
        .iter()
        .fold(String::with_capacity(64), |mut hex, b| {
            let _ = write!(hex, "{b:02x}");
            hex
        });
    Ok(Fingerprints {
        cert_sha256_hex: hex,
        spki_sha256_base64: STANDARD.encode(Sha256::digest(spki)),
    })
}

fn first_certificate(pem: &str) -> Option<Vec<u8>> {
    let start = pem.find("-----BEGIN CERTIFICATE-----")? + "-----BEGIN CERTIFICATE-----".len();
    let end = start + pem[start..].find("-----END CERTIFICATE-----")?;
    let text: String = pem[start..end].chars().filter(|c| !c.is_whitespace()).collect();
    STANDARD.decode(text).ok()
}

/// Just enough DER to find a certificate's `subjectPublicKeyInfo`.
mod der {
    /// One TLV: its tag, the whole element, its contents, and the bytes after it.
    struct Element<'a> {
        tag: u8,
        whole: &'a [u8],
        rest: &'a [u8],
        contents: &'a [u8],
    }

    /// The TLV at the start of `data`.
    fn element(data: &[u8]) -> Option<Element<'_>> {
        let tag = *data.first()?;
        let first = *data.get(1)?;
        let (length, header) = if first < 0x80 {
            (usize::from(first), 2)
        } else {
            let count = usize::from(first & 0x7f);
            if count == 0 || count > 4 {
                return None;
            }
            let bytes = data.get(2..2 + count)?;
            (
                bytes.iter().fold(0usize, |n, b| (n << 8) | usize::from(*b)),
                2 + count,
            )
        };
        let end = header.checked_add(length)?;
        let whole = data.get(..end)?;
        Some(Element {
            tag,
            whole,
            contents: &whole[header..],
            rest: &data[end..],
        })
    }

    pub(super) fn subject_public_key_info(cert: &[u8]) -> Option<&[u8]> {
        const SEQUENCE: u8 = 0x30;
        let certificate = element(cert)?;
        if certificate.tag != SEQUENCE {
            return None;
        }
        let tbs_element = element(certificate.contents)?;
        if tbs_element.tag != SEQUENCE {
            return None;
        }
        let mut tbs = tbs_element.contents;
        // Optional [0] version, then serial, signature, issuer, validity, subject, then the SPKI.
        if tbs.first() == Some(&0xa0) {
            tbs = element(tbs)?.rest;
        }
        for _ in 0..5 {
            tbs = element(tbs)?.rest;
        }
        let spki = element(tbs)?;
        (spki.tag == SEQUENCE).then_some(spki.whole)
    }
}

#[cfg(test)]
mod tests;
