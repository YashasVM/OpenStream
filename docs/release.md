# Release Guide

OpenStream Beta releases should give users direct installable assets instead of making them use GitHub source-code archives.

The Windows plugin baseline is OBS Studio **32.2.1 x64**. Its package contains only
`openstream-beta-obs.dll` and OpenStream data; it deliberately does not ship a
second copy of OBS/FFmpeg DLLs.

## Release Assets

| Asset | Audience | Purpose |
|---|---|---|
| `openstream-android.apk` | Android users | Signed install package for the OpenStream Beta camera app. |
| `openstream-android.apk.sha256` | Android users and automation | SHA-256 checksum for the exact APK in the release. |
| `openstream-android-update.json` | Android app updater | Version metadata used by the in-app update prompt. |
| `openstream-beta-obs-plugin-installer-windows-x64.exe` | Windows OBS users | Recommended one-click OBS plugin installer. |
| `openstream-beta-obs-windows-x64.zip` | Technical users | Manual plugin package with DLL and install scripts. |

## Automated Release

Create and push a version tag. The V2 beta release uses `v2.0.0-beta`:

```powershell
git tag v2.0.0-beta
git push origin v2.0.0-beta
```

The `Release` workflow builds:

| Job | Output |
|---|---|
| Android APK | `openstream-android.apk`, `openstream-android-update.json` |
| OBS plugin package | `openstream-beta-obs-windows-x64.zip` |
| OBS plugin installer | `openstream-beta-obs-plugin-installer-windows-x64.exe` |

The publish job runs only after the repository tests, Android unit tests and
lint, signed Android build, and OBS plugin build succeed. It downloads the
artifacts, normalizes the APK name, writes its SHA-256 checksum into both a
sidecar file and the Android update metadata, and runs `gh release create`.

You can also run the `Release` workflow manually from GitHub Actions and provide a tag such as `v2.0.0-beta`.

The Android job passes the release tag into Gradle as the APK `versionName` and uses the release commit timestamp as `versionCode`, so update ordering stays monotonic across full releases.

Public releases require all Android signing secrets. Missing or incomplete
signing inputs fail the workflow; it never publishes a debug-signed fallback.
Debug APKs remain available only as pull-request and local development
artifacts.

## In-App Updates

The `Android APK` workflow runs pytest, Android unit tests, lint, and a debug
streaming build for pull requests and pushes. It publishes CI artifacts only.

In-app updates follow GitHub's latest full release. The APK, checksum metadata,
Windows OBS installer, and plugin zip therefore come from the same commit. The
app verifies the APK SHA-256 digest before opening the package installer.

### Android and OBS compatibility gate

Changes to discovery, pairing, required control tokens, reservation behavior,
or media framing must ship atomically in a full release containing both the
Android APK and OBS artifacts.

For example, an Android build that introduces a required control token cannot pair with an
older OBS plugin whose beacon does not contain that token. Tests and review
must cover the old/new compatibility matrix before changing either side of the
wire contract.

### Android Signing Secrets

Configure all of these GitHub Actions secrets before publishing an Android
update or full release:

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
artifacts/openstream-beta-obs-windows-x64.zip
```

The zip contains:

```text
openstream-beta-obs.dll
data/
Install-OpenStreamBetaPlugin.ps1
install-openstream-beta-plugin.bat
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
only; do not publish that APK as an update or release.

## Release Checklist

- Confirm the README links point to the release tag being published.
- Confirm the setup guide links to the same APK, installer EXE, and plugin zip.
- Confirm pytest, Android unit tests, lint, and both production builds passed.
- Confirm `openstream-android.apk.sha256` matches the APK and the `apkSha256` metadata field.
- Confirm OBS lists `OpenStream Beta Camera` and can still load saved `openstream_phone_v7_source` scenes.
- Confirm the dependency report names `avformat-62.dll`, `avcodec-62.dll`, `avutil-60.dll`, and `swscale-9.dll`, and the clean OBS 32.2.1 log has no OpenStream module-load error.
- Seed `openstream-obs.dll`, run both installer forms, and confirm stale Program Files, ProgramData, and AppData copies were migrated without touching OBS settings or scenes.
- Confirm the Android APK is release-signed and installable over the previous public release.
- Confirm protocol-affecting Android and OBS changes are released together and pass the old/new compatibility matrix.
- Confirm the GitHub release assets are attached, not only source-code archives.
- Confirm the repository website is set to `https://openstream.pages.dev`.
- Confirm the release notes link users to [`docs/set-up.md`](set-up.md).
