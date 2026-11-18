//! Command line: `telltype-server <command> --config <file>`.

use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// Run the server.
    Serve {
        config: PathBuf,
    },
    /// Read and validate the configuration, then exit.
    CheckConfig {
        config: PathBuf,
    },
    /// Create the key and (self-signed mode) the certificate if missing, or rotate them.
    Credentials {
        config: PathBuf,
        rotate_key: bool,
        rotate_cert: bool,
    },
    /// Print the certificate's fingerprints.
    Fingerprint {
        config: PathBuf,
    },
    Help,
    Version,
}

#[must_use]
pub fn usage() -> &'static str {
    "usage: telltype-server [serve|check-config|credentials|fingerprint] --config <file.toml>\n\
     \n\
     \x20 serve         run the server (the default command)\n\
     \x20 check-config  validate the configuration and exit (exit code 2 on an error)\n\
     \x20 credentials   create the bearer key and the self-signed certificate if missing;\n\
     \x20               --rotate-key and --rotate-cert replace them (the key is never printed)\n\
     \x20 fingerprint   print the certificate's SHA-256 and SPKI pin\n\
     \n\
     The configuration file can also be given as TELLTYPE_CONFIG. Logs: TELLTYPE_LOG (default info).\n"
}

/// Parse arguments (without the program name). `env_config` is `$TELLTYPE_CONFIG`.
pub fn parse_args(args: &[String], env_config: Option<PathBuf>) -> Result<Command, String> {
    let mut command = None;
    let mut config = None;
    let (mut rotate_key, mut rotate_cert) = (false, false);
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        match arg.as_str() {
            "-h" | "--help" | "help" => return Ok(Command::Help),
            "-V" | "--version" => return Ok(Command::Version),
            "--config" | "-c" => config = Some(PathBuf::from(iter.next().ok_or("--config needs a file")?)),
            "--rotate-key" => rotate_key = true,
            "--rotate-cert" => rotate_cert = true,
            "serve" | "check-config" | "credentials" | "fingerprint" if command.is_none() => {
                command = Some(arg.clone());
            }
            other => return Err(format!("unknown argument {other:?}")),
        }
    }
    let config = config
        .or(env_config)
        .ok_or("--config <file> (or TELLTYPE_CONFIG) is required")?;
    let command = command.as_deref().unwrap_or("serve");
    if (rotate_key || rotate_cert) && command != "credentials" {
        return Err("--rotate-key and --rotate-cert belong to the credentials command".to_owned());
    }
    Ok(match command {
        "check-config" => Command::CheckConfig { config },
        "credentials" => Command::Credentials {
            config,
            rotate_key,
            rotate_cert,
        },
        "fingerprint" => Command::Fingerprint { config },
        _ => Command::Serve { config },
    })
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::{Command, parse_args, usage};

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn commands_and_options() {
        let c = PathBuf::from("/etc/t.toml");
        assert_eq!(
            parse_args(&args(&["--config", "/etc/t.toml"]), None),
            Ok(Command::Serve { config: c.clone() })
        );
        assert_eq!(
            parse_args(&args(&["check-config"]), Some(c.clone())),
            Ok(Command::CheckConfig { config: c.clone() })
        );
        assert_eq!(
            parse_args(&args(&["credentials", "-c", "/etc/t.toml", "--rotate-key"]), None),
            Ok(Command::Credentials {
                config: c.clone(),
                rotate_key: true,
                rotate_cert: false
            })
        );
        assert_eq!(
            parse_args(&args(&["fingerprint", "--config", "/etc/t.toml"]), None),
            Ok(Command::Fingerprint { config: c })
        );
        assert_eq!(parse_args(&args(&["--help"]), None), Ok(Command::Help));
        assert_eq!(parse_args(&args(&["-V"]), None), Ok(Command::Version));
        assert!(usage().contains("credentials"));
    }

    #[test]
    fn errors() {
        assert!(parse_args(&args(&[]), None).unwrap_err().contains("required"));
        assert!(parse_args(&args(&["--config"]), None).is_err());
        assert!(parse_args(&args(&["serve", "serve", "-c", "x"]), None).is_err());
        assert!(
            parse_args(&args(&["--bogus"]), None)
                .unwrap_err()
                .contains("--bogus")
        );
        assert!(parse_args(&args(&["serve", "--rotate-key", "-c", "x"]), None).is_err());
    }
}
