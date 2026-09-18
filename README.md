<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
  <img alt="OpenStream V1.0.1 Banner" src="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
</picture>

### Turn any Android phone into a wireless camera source for OBS Studio

[![Version](https://img.shields.io/badge/version-1.0.1-00D4AA?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream/releases/tag/v1.0.1)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows%20%2B%20Linux-green?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![OBS](https://img.shields.io/badge/OBS-Studio%20Plugin-purple?style=flat-square&labelColor=1a1a2e)](https://obsproject.com)
[![Website](https://img.shields.io/badge/website-openstream.pages.dev-00D4AA?style=flat-square&labelColor=1a1a2e)](https://openstream.pages.dev)

**Open-source** | **One phone, one camera** | **Hardware phone encoding** | **Local Wi-Fi**

---

</div>

> [!IMPORTANT]
> This branch simplifies OpenStream to one Android camera per OBS process and adds a native Linux build. Published V1.0.1 downloads predate these changes. Candidate builds need the device acceptance checks in [docs/testing.md](docs/testing.md) before release.

The supported V1.0.1 path is the legacy Android app plus the legacy OBS camera
source/dock over local Wi-Fi. USB transport and USB camera-source support are
not included in this release.

## Quick Downloads

| Step | Download | Install on |
|---|---|---|
| 1 | [`openstream-android.apk`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-android.apk) | Android phone |
| 2 | [`openstream-obs-plugin-installer-windows-x64.exe`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-plugin-installer-windows-x64.exe) | Windows OBS PC |
| Fallback | [`openstream-obs-windows-x64.zip`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-windows-x64.zip) | Manual OBS plugin install |

Need the non-technical walkthrough with screenshots? Start with [`docs/set-up.md`](docs/set-up.md).

---

## What is OpenStream?

OpenStream V1.0.1 sends your Android phone camera directly into OBS Studio over local Wi-Fi. It uses Camera2, MediaCodec video/audio encoding, MPEG-TS muxing, SRT transport, two-way LAN discovery, single-camera pairing, and a native OBS source plugin.

```text
Phone camera -> hardware AVC/H.264 + AAC -> SRT over Wi-Fi -> OpenStream camera source in OBS
```

### V1.0.1 Release

| Area | V1.0.1 release detail |
|---|---|
| **OBS setup** | The OpenStream Camera Control dock provides the legacy connection and camera controls. |
| **Scene compatibility** | Existing `OpenStream V7` scene sources keep loading while new sources appear as `OpenStream V8`. |
| **Pairing clarity** | One camera per OBS process, with a simple computer picker on Android. |
| **Release polish** | Version defaults, release notes, and setup docs are aligned around V1.0.1. |

## Quick Start

### 1. Install the Android App

Download the APK from the release, copy it to your Android phone, open it, and allow the install prompt. Grant camera and microphone permissions on first launch.

### 2. Install the OBS Plugin

Download and run `openstream-obs-plugin-installer-windows-x64.exe` on the Windows PC that has OBS Studio installed. Restart OBS after the installer finishes.

> [!NOTE]
> If you prefer manual installation, download `openstream-obs-windows-x64.zip`, extract it, and run `install-openstream-plugin.bat` as administrator.

### 3. Add One Camera in OBS

In OBS, click `+` in Sources, choose `OpenStream V8`, and press OK. Keep one OpenStream source. To use the camera in another scene, select **Add Existing**. If upgrading a scene with multiple camera sources, remove the extras; additional capture sessions are blocked with an explanation.

### 4. Pair the Phone

Open the OpenStream Android app on the same Wi-Fi network as the PC. Its discovery panel lists available OBS computers; tap yours to connect. The camera feed should appear in OBS within a few seconds.

### 5. Stream

Use OBS as usual. Open **Docks → OpenStream Camera Control** for the camera controls. Phone audio appears on the OpenStream source’s OBS mixer channel.

> [!TIP]
> Use a 5 GHz or Wi-Fi 6 network, keep both devices on the same subnet, and disable VPNs or router client isolation during first setup.

---

## Compatibility & Limits

| Area | Current support |
|---|---|
| **Phone OS** | Android 10+ with Camera2 and hardware MediaCodec support. |
| **OBS host** | Windows x64: OBS Studio 32.2.1. Native Linux: build against the installed OBS, Qt, and FFmpeg packages; see [Linux setup](docs/linux.md). Flatpak/Snap builds are not covered. |
| **Network** | Same LAN/subnet; guest Wi-Fi, VPNs, and client isolation can block discovery. |
| **Release maturity** | V1.0.1. Expect device-specific camera quirks and Wi-Fi-dependent latency. |

---

## Features

### Android App

| Feature | Details |
|---|---|
| **Full HD Streaming** | Streams a sustainable 1080p30 camera feed over SRT, with a 720p30 fallback profile. |
| **Hardware Encoding** | Uses an explicit hardware AVC/H.264 surface encoder; unsupported hardware is reported instead of silently using software video encoding. |
| **Audio Streaming** | Sends microphone audio with the video stream as AAC. |
| **Multi-Lens Switching** | Supports rear, ultrawide, telephoto, and front cameras when available. |
| **Pinch-to-Zoom** | Smooth digital zoom with a live zoom indicator. |
| **Screen-Off Streaming Mode** | Dims the phone to a black overlay while keeping capture and streaming active. |
| **Torch and Screen Controls** | Keeps the phone awake and can toggle torch while streaming. |
| **OBS Picker** | Choose your OBS computer and see whether the camera is available or connected. |
| **Auto-Discovery** | Finds OpenStream OBS listeners on the same LAN and advertises the phone back to OBS. |
| **Manual Connect** | Enable **Receive manual connection from phone** in OBS advanced settings, then enter the PC IP/port on the phone. Remote camera controls stay on the phone in this mode. |
| **Live Stream Telemetry** | Shows frames, keyframes, transferred megabits, errors, and active lens while streaming. |
| **Identify Overlay** | Displays a connection label on the phone when triggered from OBS. |
| **Reconnect Hold** | Keeps a reserved OBS connection for `45` seconds after a disconnect to make brief Wi-Fi drops less disruptive. |

### OBS Plugin

For native Linux build and installation instructions, see [docs/linux.md](docs/linux.md). Linux packages must match the OBS installation; do not copy a native plugin into a Flatpak or Snap installation.


| Feature | Details |
|---|---|
| **Native OBS Source** | Adds an `OpenStream V8` source type inside OBS Studio while preserving V7 scene compatibility. |
| **Camera Dock** | One camera with connect, retry, stop, zoom, torch, and lens controls. |
| **One-Click Installer** | Windows installer copies the plugin into the OBS plugin folder. |
| **Single Camera** | One active OpenStream source per OBS process. Reuse that source across scenes. |
| **Phone Discovery** | Lists discovered Android phones, includes a refresh action, and can let the phone choose the OBS computer. |
| **Auto-Connect** | Listens for the Android app and connects without typing IP addresses. |
| **Deep-Link Pairing URL** | Exposes an `openstream://connect` pairing URL with slot, port, latency, and source identity. |
| **Separate Audio Mixer** | Phone microphone audio uses the OpenStream source’s OBS mixer channel. |
| **Remote Controls** | Adjust zoom, torch, rear/front camera, and the phone identify overlay from the persistent dock. |
| **Reconnect Handling** | Reserves and releases phones per source so streams recover into the same OBS source. |

---

## UX Flow

| Moment | Experience |
|---|---|
| **First launch** | The phone explains camera/microphone access, then presents a guided discovery state over the camera preview. |
| **OBS setup** | Add one `OpenStream V8` source and control it from the Camera Control dock. |
| **Pairing** | The phone scans for available OBS computers and provides refresh, empty-state recovery, and manual fallback. |
| **Going live** | A `LIVE` badge, zoom chip, stream stats, and status text make the active connection visible at a glance. |
| **Other scenes** | Choose Add Existing to reuse your camera without opening another session. |
| **On-set checks** | Use `Identify Phone` in OBS to flash the connection label on the physical phone. |
| **Battery and heat** | Use `DISPLAY` for a black screen-off overlay or `STAY` to keep the phone awake. |

---

## Architecture

```text
Android phone
  Camera2 preview/capture
  MediaCodec hardware AVC/H.264 video
  MediaCodec AAC audio
  MPEG-TS muxer
  libsrt sender
        |
        | SRT media stream on port 9000
        v
Windows or native Linux PC
  OBS Studio
  OpenStream native source plugin
  FFmpeg SRT receive/decode
  OBS video frame + audio mixer output
```

Transport defaults (SRT `:9000`, control `:9001`, discovery `:51515`, `12 Mbps`, `120 ms`) are canonical in [docs/protocol.md](docs/protocol.md).

---

## Building

### Android App

Development build:

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> Normal APK builds link bundled libsrt static libraries for real network streaming. Use `-Popenstream.nonStreamingCiBuild=true` only for intentional source-compile checks.

Signed release build:

```powershell
$env:OPENSTREAM_RELEASE_KEYSTORE = "$PWD\openstream-release.keystore"
$env:OPENSTREAM_RELEASE_STORE_PASSWORD = "<store-password>"
$env:OPENSTREAM_RELEASE_KEY_ALIAS = "<key-alias>"
$env:OPENSTREAM_RELEASE_KEY_PASSWORD = "<key-password>"
.\gradlew.bat :app:assembleRelease
```

### OBS Plugin

For native Linux build and installation instructions, see [docs/linux.md](docs/linux.md). Linux packages must match the OBS installation; do not copy a native plugin into a Flatpak or Snap installation.


The easiest local build path on Windows is the included script:

```powershell
.\build_plugin.bat
```

To package without installing:

```powershell
$env:OPENSTREAM_SKIP_INSTALL = "1"
$env:OPENSTREAM_PLUGIN_PACKAGE_DIR = "$PWD\artifacts"
.\build_plugin.bat
```

The package output is `artifacts/openstream-obs-windows-x64.zip`.

---

## Release Notes

GitHub Actions publishes:

| Asset | Purpose |
|---|---|
| `openstream-android.apk` | Signed Android app install package |
| `openstream-obs-plugin-installer-windows-x64.exe` | Recommended Windows OBS plugin installer |
| `openstream-obs-windows-x64.zip` | Manual OBS plugin package |

See [`docs/release.md`](docs/release.md) for release tagging and validation.

---

## Developer Smoke Test

Validate SRT transport without OBS:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

This is a developer/debug tool only. Normal users should install the OBS plugin.

---

## Links

| Link | URL |
|---|---|
| Website | [OpenStream V1.0.1](https://openstream.pages.dev) |
| Releases | [github.com/YashasVM/OpenStream/releases](https://github.com/YashasVM/OpenStream/releases) |
| Issues | [github.com/YashasVM/OpenStream/issues](https://github.com/YashasVM/OpenStream/issues) |

---

<div align="center">

**Made by [@YashasVM](https://github.com/YashasVM)**

*Turn your phone into a pro OBS camera source. No cables required.*

</div>
