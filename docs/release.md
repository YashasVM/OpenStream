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

The Android job passes the release tag into Gradle as the APK `versionName` and uses the GitHub run number as `versionCode`, so the installed app version should match the release being published.

If Android signing secrets are configured, the workflow publishes a signed release APK. If they are missing, it publishes a debug-signed beta APK so preview releases can still ship with the OBS plugin assets.

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

Do not pass `-Popenstream.nonStreamingCiBuild=true` for release artifacts. If signing secrets are unavailable, validate the debug-signed fallback with `:app:assembleDebug` instead.

## Release Checklist

- Confirm the README links point to the release tag being published.
- Confirm the setup guide links to the same APK, installer EXE, and plugin zip.
- Confirm OBS lists `OpenStream V8` and can still load saved `openstream_phone_v7_source` scenes.
- Confirm the Android APK is installable on a clean phone. Prefer signed release APKs for public releases; debug-signed beta APKs are acceptable for preview tags.
- Confirm the GitHub release assets are attached, not only source-code archives.
- Confirm the repository website is set to `https://openstream.pages.dev`.
- Confirm the release notes link users to [`docs/set-up.md`](set-up.md).
