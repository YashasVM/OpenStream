# Release Guide

OpenStream releases should give users direct installable assets instead of making them use GitHub source-code archives.

## Release Assets

| Asset | Audience | Purpose |
|---|---|---|
| `openstream-android.apk` | Android users | Signed install package for the OpenStream camera app. |
| `openstream-android-update.json` | Android app updater | Version metadata used by the in-app update prompt. |
| `openstream-obs-plugin-installer-windows-x64.exe` | Windows OBS users | Recommended one-click OBS plugin installer. |
| `openstream-obs-windows-x64.zip` | Technical users | Manual plugin package with DLL and install scripts. |

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
| OBS plugin package | `openstream-obs-windows-x64.zip` |
| OBS plugin installer | `openstream-obs-plugin-installer-windows-x64.exe` |

The publish job downloads the build artifacts, normalizes the APK name, writes Android update metadata, and runs `gh release create` with the release files.

You can also run the `Release` workflow manually from GitHub Actions and provide a tag such as `v2.0.0-beta`.

The Android job passes the release tag into Gradle as the APK `versionName` and uses the release commit timestamp as `versionCode`, so update ordering stays monotonic across the full release and Android auto-update workflows.

Every public release requires all four Android signing secrets and publishes a release-signed APK. The workflow fails before building if any signing input is missing. Debug APKs remain CI artifacts only; they are never uploaded to a public release or update channel.

## Automatic Android Updates After PR Merge

The `Android APK` workflow runs the repository test suite for every push and pull request. On pushes to `main`, it additionally builds a release-signed `openstream-android.apk`, writes update metadata with its SHA-256 digest, and publishes a GitHub Release tagged `android-latest`.

Newer Android app builds check the dedicated `android-latest` release for those assets, so a merged PR can become an in-app update without manually pushing a version tag. The workflow creates that Android-only release with `--latest=false` so public `/releases/latest/download/...` links still point to the full release that includes the Windows OBS installer and zip.

The automatic-update workflow never modifies the public full-release assets. Apps should use the dedicated `android-latest` metadata and verify the published SHA-256 digest before installation.

### Android Signing Secrets

Configure these GitHub Actions secrets when you want GitHub releases to publish a signed Android APK:

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

Do not pass `-Popenstream.nonStreamingCiBuild=true` for release artifacts. If signing secrets are unavailable, use `:app:assembleDebug` only for local or CI validation; do not distribute that APK.

## Release Checklist

- Confirm the README links point to the release tag being published.
- Confirm the setup guide links to the same APK, installer EXE, and plugin zip.
- Confirm OBS lists `OpenStream V8` and can still load saved `openstream_phone_v7_source` scenes.
- Confirm the Android APK is installable on a clean phone and signed with the configured release key. Public releases and update channels must never contain debug-signed APKs.
- Confirm the GitHub release assets are attached, not only source-code archives.
- Confirm the repository website is set to `https://openstream.pages.dev`.
- Confirm the release notes link users to [`docs/set-up.md`](set-up.md).
