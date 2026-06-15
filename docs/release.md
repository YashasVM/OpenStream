# Release Guide

OpenStream publishes two release artifacts:

- Android APK: `openstream-android-debug.apk`
- OBS plugin package: `openstream-obs-windows-x64.zip`

## Automated Builds

GitHub Actions builds the Android APK on every push, pull request, and manual
workflow run. The CI APK uses the normal app build with native libsrt enabled,
so it exercises the real network streaming path.

GitHub Actions also builds the Windows x64 OBS plugin on every push, pull
request, and manual workflow run. The workflow installs OBS Studio, uses the
existing `build_plugin.bat` script, skips local installation, and uploads a zip
containing `openstream-obs.dll`.

## Creating a Release

Create and push a version tag:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

The `Release` workflow builds both artifacts and publishes a GitHub release from
that tag. You can also run the workflow manually from GitHub Actions and provide
a tag such as `v0.1.0`.

## Local Packaging

To build and package the OBS plugin without installing it into OBS:

```powershell
$env:OPENSTREAM_SKIP_INSTALL = "1"
$env:OPENSTREAM_PLUGIN_PACKAGE_DIR = "$PWD\artifacts"
.\build_plugin.bat
```

To use an OBS install outside `C:\Program Files\obs-studio`, set:

```powershell
$env:OPENSTREAM_OBS_INSTALL = "D:\Apps\obs-studio"
```

Android release validation should use the real streaming build:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

Do not pass `-Popenstream.nonStreamingCiBuild=true` for release artifacts.

