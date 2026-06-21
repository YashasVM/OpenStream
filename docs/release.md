# Release Guide

OpenStream releases should give users direct installable assets instead of making them use GitHub source-code archives.

## Release Assets

| Asset | Audience | Purpose |
|---|---|---|
| `openstream-android-debug.apk` | Android users | Installs the OpenStream camera app. |
| `openstream-obs-plugin-installer-windows-x64.exe` | Windows OBS users | Recommended one-click OBS plugin installer. |
| `openstream-obs-windows-x64.zip` | Technical users | Manual plugin package with DLL and install scripts. |

## Automated Release

Create and push a version tag:

```powershell
git tag v0.1.1-beta
git push origin v0.1.1-beta
```

The `Release` workflow builds:

| Job | Output |
|---|---|
| Android APK | `openstream-android-debug.apk` |
| OBS plugin package | `openstream-obs-windows-x64.zip` |
| OBS plugin installer | `openstream-obs-plugin-installer-windows-x64.exe` |

The publish job downloads the build artifacts, normalizes the APK name, and runs `gh release create` with the three user-facing files.

You can also run the `Release` workflow manually from GitHub Actions and provide a tag such as `v0.1.1-beta`.

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

Android release validation should use the real streaming build:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

Do not pass `-Popenstream.nonStreamingCiBuild=true` for release artifacts.

## Release Checklist

- Confirm the README links point to the release tag being published.
- Confirm the setup guide links to the same APK, installer EXE, and plugin zip.
- Confirm the GitHub release assets are attached, not only source-code archives.
- Confirm the repository website is set to `https://openstream.pages.dev`.
- Confirm the release notes link users to [`docs/set-up.md`](set-up.md).
