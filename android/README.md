# Telltype: Android build

Multi-module Gradle build for the Android app (Kotlin, Jetpack Compose, Hilt). The design is in
`../docs/PLAN.md`; module layout is section 6.2 and Appendix B, the toolchain is section 15.

## Requirements on this machine

| What | Where |
|---|---|
| JDK 17 | the system `java` (Gradle runs on it; the Kotlin and Java toolchain is 17) |
| Android SDK | `~/android-sdk`: platform `android-37.0`, build-tools 37.0.0 |
| Gradle | the wrapper (9.6.1, distribution sha256 pinned in `gradle/wrapper/gradle-wrapper.properties`) |

```bash
export ANDROID_HOME=$HOME/android-sdk      # or put sdk.dir=... in local.properties (git-ignored)
cd android
./gradlew check assembleDebug              # every gate, then the debug APK
```

**Memory.** `gradle.properties` caps the Gradle daemon at 4 GB and the Kotlin daemon at 2 GB; each test JVM
gets 2 GB. Inside a 4 GB agent memory scope a full build can be killed (exit 137 or 143). Run it as a
transient user unit instead and read the log afterwards:

```bash
systemd-run --user --wait --collect -p MemoryMax=infinity -E ANDROID_HOME=$HOME/android-sdk \
  --working-directory=$PWD /bin/bash -c './gradlew check assembleDebug > /tmp/gradle.log 2>&1'
```

Run `./gradlew --stop` when you finish so no daemon keeps holding memory.

## Common tasks

| Task | Command |
|---|---|
| All gates (what must pass before a commit) | `./gradlew check` |
| Debug APK (`net.jbglenn.telltype.debug`, LeakCanary, StrictMode) | `./gradlew :app:assembleDebug` |
| Release APK (R8, resource shrinking) | `./gradlew :app:assembleRelease` |
| Unit tests of one module | `./gradlew :domain:test` or `./gradlew :ui:designsystem:testDebugUnitTest` |
| Fix formatting | `./gradlew spotlessApply` |
| Record screenshot goldens after an intended UI change | `./gradlew :ui:designsystem:recordRoborazziDebug` |
| Compile the UI Automator tests | `./gradlew :testhost:assembleDebugAndroidTest` |
| Run them on an emulator or phone | `./gradlew :testhost:connectedDebugAndroidTest` |

## What `check` runs

- **Kotlin warnings are errors** in every module (`allWarningsAsErrors`); the pure modules also use
  **explicit API mode**.
- **Android Lint** on every Android module: `warningsAsErrors`, `abortOnError`, no baseline. Shared settings
  are in `config/lint/lint.xml`; each suppression there carries its reason.
- **ktlint 1.8.0** through Spotless (`spotlessCheck`), `android_studio` style, rules in `.editorconfig`.
  The root project also checks the build files and `build-logic`.
- **detekt 1.23.8** with the **Compose rules** (`io.nlopez.compose.rules:detekt` 0.4.28), config in
  `config/detekt/detekt.yml` on top of the defaults. No baseline.
- **Unit tests.** Pure modules: JUnit Jupiter. Android modules: JUnit 4 with Robolectric (debug variant only;
  release host tests are disabled). Robolectric runs on SDK 35, set in each module's
  `src/test/resources/robolectric.properties`, because SDK 36 and 37 need a Java 21 test JVM.
- **Screenshot tests** (Roborazzi) in every Compose module: `check` runs `verifyRoborazziDebug` against the
  goldens in `src/test/screenshots/`. A missing or changed golden fails the build.
- **The module dependency rule** (`checkModuleDependencies`, below). Kotlin compilation also depends on it.

## Modules and the dependency rule

| Module | Kind | May depend on (production code) |
|---|---|---|
| `:core:model` | pure Kotlin/JVM | nothing |
| `:core:common` | pure Kotlin/JVM | `:core:model` |
| `:domain` | pure Kotlin/JVM | `:core:*` |
| `:data`, `:network`, `:audio`, `:platform` | Android library | `:domain`, `:core:*` (never each other, never `:ui:*`) |
| `:ui:designsystem` | Android library + Compose | `:domain`, `:core:*` |
| `:ui:overlay`, `:ui:screens` | Android library + Compose | `:domain`, `:core:*`, `:ui:designsystem` |
| `:testing` | pure Kotlin/JVM, shared fakes | `:domain`, `:core:*` |
| `:app` | application | every library module (not `:testing`) |
| `:testhost` | application for UI Automator tests | `:core:*`, `:ui:designsystem` |

Test configurations (`testImplementation`, `androidTestImplementation`, ...) may additionally use `:testing`.
The pure modules have no Android plugin, so an Android import there does not compile.

The rule lives in `build-logic/.../ModuleDependencyRules.kt`. A violation fails the build like this:

```
> Module dependency rule broken (docs/PLAN.md section 6.2):
    :data --implementation--> :network
      rule: :data, :network, :audio and :platform depend only on :domain and :core:*, never on each other or on :ui:*
```

## Adding a module

1. Create the directory and add it to `include(...)` in `settings.gradle.kts`.
2. Classify it in `ModuleDependencyRules.layerOf` (an unclassified module fails `checkModuleDependencies`).
3. Write a `build.gradle.kts` of a few lines with the matching convention plugins:

   ```kotlin
   plugins {
       alias(libs.plugins.telltype.android.library)   // or telltype.jvm.library for pure Kotlin
       alias(libs.plugins.telltype.android.compose)   // only for Compose UI
       alias(libs.plugins.telltype.hilt)              // only if it has Hilt modules or @Inject classes
   }

   dependencies {
       implementation(projects.domain)
       testImplementation(projects.testing)
   }
   ```

   The Android namespace is derived from the path (`:ui:overlay` becomes `net.jbglenn.telltype.ui.overlay`).
   Sources go in `src/main/kotlin/<namespace as directories>`.
4. If it has Robolectric tests, copy `robolectric.properties` into its `src/test/resources`.

## Convention plugins (`build-logic/`)

| Plugin id | Does |
|---|---|
| `telltype.jvm.library` | Kotlin/JVM, toolchain 17, explicit API, warnings as errors, quality gates, tests |
| `telltype.android.library` | AGP library with built-in Kotlin, SDK levels, Lint gates, quality gates, tests |
| `telltype.android.application` | AGP application, target SDK, BuildConfig, R8 release build, quality gates, tests |
| `telltype.android.compose` | Compose compiler, Compose BOM and Material 3, Roborazzi |
| `telltype.hilt` | Hilt with KSP (no kapt) |
| `telltype.test` | JUnit Jupiter (pure) or JUnit 4 + Robolectric (Android); applied by the three module plugins |

AGP 9 compiles Kotlin itself ("built-in Kotlin"), so `org.jetbrains.kotlin.android` is not applied anywhere.

## Versions and dependencies

Every version is in `gradle/libs.versions.toml`; module build files never contain a version. A new library goes
into the catalog first, with a one-line reason.

**Dependency verification is on** (`gradle/verification-metadata.xml`, sha256). After adding or upgrading a
dependency, regenerate it and review the diff:

```bash
./gradlew --write-verification-metadata sha256 check assembleDebug :app:assembleRelease :testhost:assembleDebugAndroidTest
```

## Build types

- **debug:** application id suffix `.debug`, LeakCanary, StrictMode (disk or network on the main thread
  kills the process) enabled in `TelltypeApp` when `BuildConfig.DEBUG`.
- **release:** R8 minification and resource shrinking, rules in `app/proguard-rules.pro`. Signing is read from
  `~/.config/telltype/release-signing.properties` (keys `storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`), or from the file named by `-PtelltypeSigningProperties=...`. Without it the APK is unsigned.
  No keystore is ever kept in this repository.

## Gradle settings

Configuration cache, build cache and parallel execution are on; R classes are non-transitive; repositories
are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`).
