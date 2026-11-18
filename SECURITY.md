# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Report it privately through GitHub's private
vulnerability reporting:

**<https://github.com/joeglenn/telltype/security/advisories/new>**

(or the repository's **Security** tab → **Report a vulnerability**).

Include what you found, the version of the app or server, and the steps to reproduce it. Do not include real server
keys, certificates or recordings; made-up values are fine.

You should get an answer within a week. Once a fix is released, the advisory is published with credit to you,
unless you prefer otherwise.

## What is in scope

- The Telltype Android app (`android/`).
- Telltype Server (`server-rs/`).
- The release process: the signed APK on GitHub Releases and the workflows in `.github/workflows/`.

Problems in third-party servers that Telltype talks to (llama.cpp, other OpenAI-compatible servers) belong with
those projects.

## Supported versions

Security fixes go into the latest release. Please update before reporting, and say which version you tested.

## How Telltype protects your data

A short summary; [PRIVACY.md](PRIVACY.md) has the details.

- The app talks only to the server address you enter. HTTPS uses the system trust store or a self-signed
  certificate pinned after you confirm its fingerprint; plain HTTP is refused except to private addresses.
- The server key is stored encrypted with an AES-256-GCM key held in the Android Keystore. Android backup is off.
- Recordings are deleted after the text is typed. Logs do not contain dictated text unless verbose debug logging
  is switched on, and that switches itself off after an hour.
- Telltype Server compares the bearer key in constant time, decodes audio in memory without temporary files, and
  never logs audio, transcripts, prompts, replies or keys. For a server reachable from the internet (connection
  timeouts and caps, per-address rate limits, model allow-lists, named revocable keys, the sandboxed unit, a reverse
  proxy example) see [Running on the internet](server-rs/README.md#running-on-the-internet).
- Release APKs are shrunk with R8 and signed; dependencies are pinned and checked with Gradle dependency
  verification and `cargo deny`; workflow actions are pinned by commit SHA.
