# Contributing to Telltype

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

- **Bugs and ideas:** open an [issue](https://github.com/joeglenn/telltype/issues/new/choose).
- **Security problems:** report them privately, see [SECURITY.md](SECURITY.md).
- **Larger changes:** open an issue first so we can agree on the approach before you spend time on code.

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE), like the rest
of the project.

## Repository layout

| Path | What |
|---|---|
| `android/` | The Android app: Kotlin, Jetpack Compose, Hilt; a multi-module Gradle build (`:core:*`, `:domain`, `:data`, `:network`, `:audio`, `:platform`, `:ui:*`, `:app`, plus `:testhost`, a small app with text fields for the device tests) |
| `server-rs/` | Telltype Server (Rust workspace); see [server-rs/README.md](server-rs/README.md) |
| `shared/` | Cleanup and command prompts and guard data, used by the app and the evaluation |
| `eval/` | The cleanup evaluation (golden set and scripts) |

## Android app

You need JDK 17 and the Android SDK (platform 37, build-tools 37.0.0). Point Gradle at the SDK with `ANDROID_HOME`
or `sdk.dir=` in `android/local.properties`.

```sh
cd android
./gradlew assembleDebug                 # debug APK (application id net.jbglenn.telltype.debug)
./gradlew check                         # every gate below; must pass before a pull request
./gradlew spotlessApply                 # fix formatting
./gradlew :ui:designsystem:recordRoborazziDebug   # re-record screenshot goldens after an intended UI change
```

`check` runs:

- **Kotlin** with warnings as errors; the pure modules use explicit API mode.
- **Android Lint** with warnings as errors and no baseline.
- **ktlint** through **Spotless** (`spotlessCheck`).
- **detekt** with the Compose rules, no baseline.
- **Unit tests**: JUnit Jupiter in pure modules, JUnit 4 with Robolectric in Android modules.
- **Screenshot tests** (Roborazzi) against the goldens in each module's `src/test/screenshots/`.
- **The module dependency rule** (`checkModuleDependencies`): modules may depend only on the layers below them.

**Device tests** (UI Automator, in `app/src/androidTest` and `testhost`) need an emulator or phone and a running
server. `android/scripts/connected-tests.sh <adb serial>` builds, installs and runs them against a Telltype Server
on the same machine (the emulator reaches it at `https://10.0.2.2:8170`) and prints a pass/fail table. Emulators
have no microphone; debug builds can play a bundled clip instead (`android/scripts/deploy.sh <serial>
--clip-microphone`).

**Dependencies** are pinned in `android/gradle/libs.versions.toml` and checked with Gradle dependency verification
(`android/gradle/verification-metadata.xml`). If you add or update one, regenerate the metadata
(`./gradlew --write-verification-metadata sha256 check assembleDebug`) and say so in the pull request.

## Telltype Server

You need Rust 1.94 or newer, plus `cargo-llvm-cov`, `cargo-deny` and the `llvm-tools-preview` component for the gate.

```sh
cd server-rs
cargo fmt                               # rustfmt, configured in rustfmt.toml
scripts/gate.sh                         # fmt check, clippy (pedantic, -D warnings), tests with an 85 % line
                                        # coverage floor, cargo deny; logs in target/gate/
SLOW=1 TELLTYPE_PARAKEET_DIR=<model dir> scripts/gate.sh   # adds the real-model test
TELLTYPE_CONFIG_DIR=~/.config/telltype scripts/smoke.sh https://127.0.0.1:8170   # a running server, end to end
```

`scripts/contract.py` checks a server against the HTTP contract in
[server-rs/docs/contract.md](server-rs/docs/contract.md). Change the contract and the code together.

## Pull requests

- Keep a pull request to one change. Add or update tests for what you change; UI changes come with updated
  screenshot goldens.
- Run `./gradlew check` (app) or `scripts/gate.sh` (server) before you push. CI runs the same gates
  (`.github/workflows/android.yml`, `server-rs.yml`).
- Never commit secrets, real server addresses, certificate fingerprints or recordings of real people. Use
  documentation values such as `192.0.2.10` and `server.example`.
- Write for a reader who does not know the code: plain English, comments that say why.

### Commit messages

- A subject line in the imperative mood, at most 72 characters, without a trailing period: "Keep the bubble above
  the keyboard after rotation".
- A blank line, then a body that explains what changed and why, wrapped at 72 to 100 characters.
- Mention the issue it fixes: `Fixes #123`.
