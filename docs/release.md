# Release Guide

OpenStream releases should give users direct installable assets instead of making them use GitHub source-code archives.

The Windows plugin baseline is OBS Studio **32.2.1 x64**. Its package contains only
`openstream-obs.dll`; it deliberately does not ship a second copy of OBS/FFmpeg
DLLs. Linux packages are built on Ubuntu 24.04 and use the host's OBS, FFmpeg,
and Qt libraries. Install them only on compatible x86_64 distributions; the
archive does not bundle those system libraries.

## Release Assets

| Asset | Audience | Purpose |
|---|---|---|
| `openstream-android.apk` | Android users | Signed install package for the OpenStream camera app. |
| `openstream-android.apk.sha256` | Android users and automation | SHA-256 checksum for the exact APK in the release. |
| `openstream-obs-plugin-installer-windows-x64.exe` | Windows OBS users | Recommended one-click OBS plugin installer. |
| `openstream-obs-plugin-installer-windows-x64.exe.sha256` | Windows users and automation | SHA-256 checksum for the installer. |
| `openstream-obs-windows-x64.zip` | Technical users | Manual plugin package with DLL and install scripts. |
| `openstream-obs-windows-x64.zip.sha256` | Technical users and automation | SHA-256 checksum for the exact manual plugin package. |
| `openstream-obs-linux-x86_64.tar.gz` | Native Linux OBS users | Linux plugin module and install script for compatible distro OBS builds. |
| `openstream-obs-linux-x86_64.tar.gz.sha256` | Linux users and automation | SHA-256 checksum for the exact Linux package. |

### Android compatibility and updates

The release APK keeps the published application ID `dev.openstream.app` and
release signing identity so existing installs can update in place.
Android can update an existing installation only when the application ID and
signing certificate match and the new APK has a higher version code. The release
workflow checks the version code against prior tagged releases and compares the
APK certificate with the latest published APK. Keep the four Android signing
secrets below stable; replacing the keystore or alias prevents in-place updates
and requires users to uninstall first, losing app data.

The APK is universal with `minSdk 29` and native libraries for `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`. Camera lens availability and device-specific
Camera2 behavior still require checking on target phones.

## Automated Release

Set both `openstream.versionName` and `openstream.versionCode` in
`android/gradle.properties`, then create and push a matching `vMAJOR.MINOR.PATCH`
tag that points to that commit. The version name must equal the tag without its
leading `v`; the version code must be a positive integer greater than every
previous release code and no greater than Android's 2,100,000,000 limit.

```powershell
git tag v1.0.1
git push origin v1.0.1
```

The same workflow can be run manually with an already pushed release tag. It
builds the commit referenced by that tag and rejects malformed or mismatched
tags. It does not create a release from the current default branch. Older
releases used the tag commit timestamp as the Android version code; the
workflow uses that value when checking historical tags without the canonical
`openstream.versionCode` property.

The `Release` workflow builds:

| Job | Output |
|---|---|
| Android APK | `openstream-android.apk`, `openstream-android.apk.sha256` |
| OBS plugin package | `openstream-obs-windows-x64.zip`, `openstream-obs-windows-x64.zip.sha256` |
| OBS plugin installer | `openstream-obs-plugin-installer-windows-x64.exe`, `openstream-obs-plugin-installer-windows-x64.exe.sha256` |
| Linux OBS plugin | `openstream-obs-linux-x86_64.tar.gz`, `openstream-obs-linux-x86_64.tar.gz.sha256` |

The publish job runs only after repository tests, Android unit tests and lint,
the signed Android build, and both Windows and Linux plugin builds succeed. It
downloads all artifacts, verifies each published binary's SHA-256 sidecar, and
then runs `gh release create`.

Public releases require all Android signing secrets. Missing or incomplete
signing inputs fail the workflow; it never publishes a debug-signed fallback.
Debug APKs remain available only as pull-request and local development
artifacts.

### Android and OBS compatibility gate

Changes to discovery, pairing, required control tokens, reservation behavior,
or media framing must ship atomically in a full release containing both the
Android APK and OBS artifacts.

For example, an Android build that introduces a required control token cannot pair with an
older OBS plugin whose beacon does not contain that token. Tests and review
must cover the old/new compatibility matrix before changing either side of the
wire contract.

### Android Signing Secrets

Configure all of these GitHub Actions secrets before publishing a full release:

| Secret | Purpose |
|---|---|
| `OPENSTREAM_RELEASE_KEYSTORE_BASE64` | Base64-encoded Android keystore file. |
| `OPENSTREAM_RELEASE_STORE_PASSWORD` | Keystore password. |
| `OPENSTREAM_RELEASE_KEY_ALIAS` | Release key alias. |
| `OPENSTREAM_RELEASE_KEY_PASSWORD` | Release key password. |

## Local Plugin Packaging

Build and package the OBS plugin without installing it locally:

```powershell
$env:OPENSTREAM_SKIP_INSTALL = "1"
$env:OPENSTREAM_PLUGIN_PACKAGE_DIR = "$PWD\artifacts"
.\build_plugin.bat
```

Output:

```text
artifacts/openstream-obs-windows-x64.zip
```

The zip contains:

```text
openstream-obs.dll
Install-OpenStreamPlugin.ps1
install-openstream-plugin.bat
```

To use an OBS install outside `C:\Program Files\obs-studio`, set:

```powershell
$env:OPENSTREAM_OBS_INSTALL = "D:\Apps\obs-studio"
```

## Local Android Build

Android release validation should use the real streaming build. Signed release validation requires a keystore:

```powershell
cd android
$env:OPENSTREAM_RELEASE_KEYSTORE = "$PWD\openstream-release.keystore"
$env:OPENSTREAM_RELEASE_STORE_PASSWORD = "<store-password>"
$env:OPENSTREAM_RELEASE_KEY_ALIAS = "<key-alias>"
$env:OPENSTREAM_RELEASE_KEY_PASSWORD = "<key-password>"
.\gradlew.bat :app:assembleRelease
```

Do not pass `-Popenstream.nonStreamingCiBuild=true` for release artifacts. If
signing secrets are unavailable, use `:app:assembleDebug` for local validation
only; do not publish that APK as a release.

## Release Checklist

- Confirm the README links point to the release tag being published.
- Confirm the setup guide links to the same APK, installer EXE, and plugin zip.
- Confirm pytest, Android unit tests, lint, and Android, Windows, and Linux release builds passed.
- Confirm `openstream-android.apk.sha256` matches the APK.
- Confirm `openstream-obs-plugin-installer-windows-x64.exe.sha256` matches the installer.
- Confirm `openstream-obs-windows-x64.zip.sha256` matches the manual plugin package.
- Confirm `openstream-obs-linux-x86_64.tar.gz.sha256` matches the Linux package.
- Confirm OBS lists `OpenStream V8` and can still load saved `openstream_phone_v7_source` scenes.
- Confirm the dependency report names `avformat-62.dll`, `avcodec-62.dll`, `avutil-60.dll`, and `swscale-9.dll`, and the clean OBS 32.2.1 log has no OpenStream module-load error.
- Seed `openstream-obs.dll`, run both installer forms, and confirm stale Program Files, ProgramData, and AppData copies were migrated without touching OBS settings or scenes.
- Confirm the Android APK is release-signed and installable over the previous public release.
- Confirm the signing identity matches the previous release and the version code increased.
- Confirm protocol-affecting Android and OBS changes are released together and pass the old/new compatibility matrix.
- Confirm the GitHub release assets are attached, not only source-code archives.
- Confirm the repository website is set to `https://openstream.pages.dev`.
- Confirm the release notes link users to [`docs/set-up.md`](set-up.md).
