<h1 align="center">Streamflix Reborn</h1>

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="100px" />
  <br />
  <strong>🔄 Reborn Version</strong> - Community continuation of the original Streamflix project
  <br />
  An open-source Android TV app for educational streaming interface, made with Android Studio, in Kotlin
  <br />
  <a href="https://github.com/streamflix-reborn/streamflix/releases/latest">
    <strong>Download app »</strong>
  </a>
  <br />
  <br />
  <a href="https://github.com/streamflix-reborn/streamflix/issues">Report Bug</a>
  ·
  <a href="https://github.com/streamflix-reborn/streamflix/issues">Request Feature</a>
</p>

<details>
  <summary>Table of Contents</summary>

- [About the project](#about-the-project)
  - [What is Streamflix Reborn?](#-what-is-streamflix-reborn)
  - [Features](#features)
  - [Built with](#built-with)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Setup](#setup)
- [Development](#development)
- [Contributing](#contributing)
- [Legal Disclaimer](#legal-disclaimer)
- [Credits & Authors](#credits--authors)
- [License](#license)
</details>

## About the project

<p align="center">
  <img src="./.github/docs/screenshot.png" alt="Streamflix Preview">
</p>

**Streamflix Reborn** is an independent continuation of the original Streamflix project created by [Lory-Stan TANASI](https://github.com/stantanasi). This reborn version maintains the same educational purpose and functionality while ensuring continued development and support.

### 🔄 What is Streamflix Reborn?

- **Independent Continuation**: This is an independent continuation of the original Streamflix project
- **Same Vision**: Maintains the original educational and open-source philosophy
- **Enhanced Support**: Continued development and bug fixes by an independent developer
- **Respectful Fork**: Built with full respect for the original creator's work

Streamflix Reborn is an open-source Android TV app that provides a user interface for accessing publicly available streaming content from various third-party providers.

This app is designed for educational purposes and personal use only. Users are responsible for ensuring they have proper authorization to access any content they view through this application.

The interface aggregates content from multiple sources and provides a convenient way to browse available streaming options.

### Features

- Open-source and ad-free interface
- Aggregates content from multiple third-party providers
- No account required for the app interface
- Educational and personal use only
- Optimized UI & UX
- Multiple providers
- Resume from last playback position
- In-app update

### Built with

- [Android Studio](https://developer.android.com/studio)
- [Kotlin](https://kotlinlang.org)
- [Retrofit](https://square.github.io/retrofit)
- [ExoPlayer](https://exoplayer.dev)
- Leanback
- Coroutines
- MVVM Architecture
- Android Architecture Components


## Getting started

### Prerequisites

- **JDK 17 or 21**. The project uses Gradle 8.14 through the included wrapper; a separate Gradle installation is not needed.
- **Android Studio** with its SDK Manager, or an Android SDK command-line installation.
- **Android SDK Platform 36** and the SDK Platform Tools. Install the Build Tools version requested by the Android Gradle plugin if the build asks for it.

With Android Studio, install the SDK from **Settings → Languages & Frameworks → Android SDK**. For a terminal-only setup, set `ANDROID_HOME` to your SDK directory, or let Android Studio add `sdk.dir` to `local.properties`.

### Setup

1. Clone and open the project:

```bash
git clone https://github.com/streamflix-reborn/streamflix.git
cd streamflix
```

2. Open this directory in Android Studio and let it sync, or build directly from the terminal:

```bash
# macOS / Linux
./gradlew :app:assembleDebug

# Windows PowerShell
.\gradlew.bat :app:assembleDebug
```

**No `local.properties` file or service credentials are required to configure and build the app.** Missing optional settings become empty strings, never the literal string `null`. Features that require a service still need their corresponding configuration at runtime; a successful build does not enable those services.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. In Android Studio, you can also select a device and click **Run**.

### Build configuration

This project supports **Android TV only**. There is one manifest, one debug build and one release asset. Legacy mobile source remains temporarily where shared code still references it, but mobile/universal APKs and launchers are no longer produced. `APP_LAYOUT` defaults to `tv`; explicitly selecting `mobile` or `universal` fails configuration.

Configuration is resolved in this order, from highest to lowest priority:

1. Gradle properties: `-PAPP_LAYOUT=tv`, `gradle.properties`, or `ORG_GRADLE_PROJECT_APP_LAYOUT=tv`.
2. Environment variables: `APP_LAYOUT=tv`.
3. The optional root `local.properties` file.
4. Defaults: `APP_LAYOUT=tv`; all optional service settings are empty.

An explicitly empty or missing layout selects `tv`. Other values fail with an actionable error.

For file-based configuration, copy the included template. Keep real credentials out of Git:

```bash
# macOS / Linux
cp local.properties.example local.properties

# Windows PowerShell
Copy-Item local.properties.example local.properties
```

| Setting | Purpose |
| --- | --- |
| `APP_LAYOUT` | `tv` only; normally omit it. |
| `TMDB_API_KEY` | Optional default TMDb key; a key entered in app settings takes precedence. |
| `SUBDL_API_KEY` | Optional default SubDL key; a key entered in app settings takes precedence. |
| `RABBITSTREAM_SOURCE_API` | Optional Rabbitstream sources endpoint prefix, including its trailing slash. Without it, that extractor reports a configuration error. |

BuildConfig values are packaged into the APK and **are not secret storage**. Do not use privileged server credentials here.

To inspect the selected configuration without printing any keys:

```bash
./gradlew :app:printBuildConfiguration
```

### Development and automated tests

```bash
# Offline policy/parser tests and the single Android TV debug APK
./gradlew :app:testDebugUnitTest :app:assembleDebug

# Dependency-free build configuration and manifest checks
./gradlew -p gradle/build-config-tests check --no-configuration-cache

# Instrumentation on a connected Android TV device or TV emulator
./gradlew :app:connectedDebugAndroidTest

# Release build (sign before installing)
./gradlew clean :app:assembleRelease
```

Instrumentation covers real Room/SQLite persistence across database reopen, the TV manifest and launcher, provider-screen D-pad focus, and the real `PlayerTvView` with a simulated Media3 player. Playback, IPTV parsing, subtitle selection and TV update selection also have deterministic offline unit tests. These tests do not certify third-party stream availability or replace testing on physical TV hardware.

Release output is `app/build/outputs/apk/release/`. CI publishes only `streamflix-<tag>-tv.apk`.

This suffix matches the TV-only in-app updater. Publishing requires the `KEYSTORE`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, and `SIGNING_KEY_PASSWORD` repository secrets. Service settings can be supplied through the corresponding repository secrets and are passed as environment variables, not written to files.

The **Android TV quality** workflow runs configuration checks, offline unit tests, a TV debug build, and instrumentation on an API 29 Android TV emulator (`tv_1080p`, x86). It runs on pull requests, pushes to `main`/`arena/**`, and manual dispatch. The release workflow reuses this quality gate before signing or publishing; a failed test blocks publication. CI needs no signing or service secrets for the quality checks.

See [`docs/testing-tv.md`](docs/testing-tv.md) for scope, local emulator setup and troubleshooting.
