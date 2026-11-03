<h1 align="center">Streamflix Reborn</h1>

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="100px" />
  <br />
  <strong>🔄 Reborn Version</strong> - Community continuation of the original Streamflix project
  <br />
  An open-source Android TV and mobile app for educational streaming interface, made with Android Studio, in Kotlin
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

Streamflix Reborn is an open-source Android TV and mobile app that provides a user interface for accessing publicly available streaming content from various third-party providers.

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

Configuration is resolved in this order, from highest to lowest priority:

1. Gradle properties: `-PAPP_LAYOUT=mobile`, `gradle.properties`, or `ORG_GRADLE_PROJECT_APP_LAYOUT=mobile`.
2. Environment variables: `APP_LAYOUT=mobile`.
3. The optional root `local.properties` file.
4. Defaults: `APP_LAYOUT=universal`; all optional service settings are empty.

An explicitly empty Gradle property overrides lower-priority sources. An empty or missing layout selects `universal`. Other invalid layout values fail configuration with an actionable error instead of silently selecting a different manifest.

For file-based configuration, copy the included template. Keep real credentials out of Git:

```bash
# macOS / Linux
cp local.properties.example local.properties

# Windows PowerShell
Copy-Item local.properties.example local.properties
```

| Setting | Purpose |
| --- | --- |
| `APP_LAYOUT` | `universal` (mobile and TV launchers), `mobile`, or `tv`. |
| `TMDB_API_KEY` | Optional default TMDb key; a key entered in app settings takes precedence. |
| `SUBDL_API_KEY` | Optional default SubDL key; a key entered in app settings takes precedence. |
| `RABBITSTREAM_SOURCE_API` | Optional Rabbitstream sources endpoint prefix, including its trailing slash. Without it, that extractor reports a configuration error. |

BuildConfig values are packaged into the APK and **are not secret storage**. Do not use privileged server credentials here.

To inspect the selected configuration without printing any keys:

```bash
./gradlew :app:printBuildConfiguration
```

### Development

Build a specific layout with the same property locally and in CI:

```bash
./gradlew -PAPP_LAYOUT=universal :app:assembleDebug
./gradlew -PAPP_LAYOUT=mobile :app:assembleDebug
./gradlew -PAPP_LAYOUT=tv :app:assembleDebug
```

When switching layouts in an existing checkout, use `clean` to remove the previous layout's outputs:

```bash
./gradlew clean :app:assembleRelease -PAPP_LAYOUT=tv
```

Release builds are written to `app/build/outputs/apk/release/` and must be signed before installation. CI builds each release layout in a separate job and publishes these names:

- Universal: `streamflix-<tag>.apk`
- Mobile: `streamflix-<tag>-mobile.apk`
- TV: `streamflix-<tag>-tv.apk`

These suffixes match the in-app updater. Publishing requires the `KEYSTORE`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, and `SIGNING_KEY_PASSWORD` repository secrets. Service settings can be supplied through the corresponding repository secrets and are passed as environment variables, not written to files.

Run the dependency-free build-configuration regression checks with:

```bash
./gradlew -p gradle/build-config-tests check --no-configuration-cache
```

The **Build check** workflow runs these checks and builds all three debug layouts on pull requests, pushes to `main`/`arena/**`, and manual dispatch. It does not need signing or service secrets.

## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a pull request

## Legal Disclaimer

**IMPORTANT: This application is for educational and personal use only.**

- Streamflix does not host, store, or distribute any copyrighted content
- All content is sourced from third-party providers and websites
- Users are solely responsible for ensuring they have legal rights to access any content
- The developers do not endorse or encourage copyright infringement
- Users must comply with all applicable laws in their jurisdiction
- Any legal issues should be directed to the actual content providers
- This app functions as a search engine aggregator only
- No copyrighted material is stored on our servers

## Legal Notice

This application is provided "as is" for educational purposes. The developers:
- Do not claim ownership of any content
- Do not profit from copyrighted material
- Do not control third-party content providers
- Encourage users to support content creators through legal means
- Recommend using official streaming services when available

## Credits & Authors

### Original Creator
- **[Lory-Stan TANASI](https://github.com/stantanasi)** - Original Streamflix project creator

### Reborn Development
- **Independent Developer** - Streamflix Reborn maintainer
- **Special thanks** to the original creator for the excellent foundation

## License

This project is licensed under the `Apache-2.0` License - see the [LICENSE](LICENSE) file for details

### Original Project
<p align="center">
  <br />
  © 2022 Lory-Stan TANASI. All rights reserved
</p>

### Reborn Project
<p align="center">
  <br />
  © 2025 Streamflix Reborn. Built with respect for the original work.
</p>