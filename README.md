<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
  <img alt="OpenStream V1.0.1 Banner" src="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
</picture>

### Turn any Android phone into a wireless camera source for OBS Studio

[![Version](https://img.shields.io/badge/version-1.0.1-00D4AA?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream/releases/tag/v1.0.1)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows-green?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![OBS](https://img.shields.io/badge/OBS-Studio%20Plugin-purple?style=flat-square&labelColor=1a1a2e)](https://obsproject.com)
[![Website](https://img.shields.io/badge/website-openstream.pages.dev-00D4AA?style=flat-square&labelColor=1a1a2e)](https://openstream.pages.dev)

**Open-source** | **Low-latency** | **Hardware-accelerated** | **Local Wi-Fi**

---

</div>

> [!IMPORTANT]
> OpenStream V1.0.1 is a fixed patch release for local Wi-Fi camera workflows. Device-specific camera behavior and network quality can still vary; please report bugs in [GitHub Issues](https://github.com/YashasVM/OpenStream/issues).

## Quick Downloads

| Step | Download | Install on |
|---|---|---|
| 1 | [`openstream-android.apk`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-android.apk) | Android phone |
| 2 | [`openstream-obs-plugin-installer-windows-x64.exe`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-plugin-installer-windows-x64.exe) | Windows OBS PC |
| Fallback | [`openstream-obs-windows-x64.zip`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-windows-x64.zip) | Manual OBS plugin install |

Need the non-technical walkthrough with screenshots? Start with [`docs/set-up.md`](docs/set-up.md).

---

## What is OpenStream?

OpenStream V1.0.1 sends your Android phone camera directly into OBS Studio over local Wi-Fi. It uses Camera2, MediaCodec video/audio encoding, MPEG-TS muxing, SRT transport, two-way LAN discovery, source-slot pairing, and a native OBS source plugin.

```text
Phone camera -> HEVC/H.264 + AAC -> SRT over Wi-Fi -> OpenStream camera slot in OBS
```

### V1.0.1 Release

| Area | V1.0.1 release detail |
|---|---|
| **OBS setup** | The OpenStream Camera Control dock provides the legacy connection and camera controls. |
| **Scene compatibility** | Existing `OpenStream V7` scene sources keep loading while new sources appear as `OpenStream V8`. |
| **Pairing clarity** | OBS and Android both describe slots as production camera positions instead of raw listener ports. |
| **Release polish** | Version defaults, release notes, and setup docs are aligned around V1.0.1. |

## Quick Start

### 1. Install the Android App

Download the APK from the release, copy it to your Android phone, open it, and allow the install prompt. Grant camera and microphone permissions on first launch.

### 2. Install the OBS Plugin

Download and run `openstream-obs-plugin-installer-windows-x64.exe` on the Windows PC that has OBS Studio installed. Restart OBS after the installer finishes.

> [!NOTE]
> If you prefer manual installation, download `openstream-obs-windows-x64.zip`, extract it, and run `install-openstream-plugin.bat` as administrator.

### 3. Add Camera Slots in OBS

In OBS, click `+` in Sources, choose `OpenStream V8`, keep the camera slot enabled, and press OK. Each source gets a slot such as `CAM A` or `CAM B`, so multi-phone setups can stay organized.

### 4. Pair the Phone

Open the OpenStream Android app on the same Wi-Fi network as the PC. Its guided discovery panel lists available OBS camera slots; tap one to connect. The camera feed should appear in OBS within a few seconds.

### 5. Stream

Use OBS as usual. Open **Docks → OpenStream Camera Control** for the camera controls. Phone audio appears as a separate OBS mixer channel.

> [!TIP]
> Use a 5 GHz or Wi-Fi 6 network, keep both devices on the same subnet, and disable VPNs or router client isolation during first setup.

---

## Compatibility & Limits

| Area | Current support |
|---|---|
| **Phone OS** | Android 10+ with Camera2 and hardware MediaCodec support. |
| **OBS host** | Windows x64 with OBS Studio 32.2.1 and its bundled FFmpeg SRT support. |
| **Network** | Same LAN/subnet; guest Wi-Fi, VPNs, and client isolation can block discovery. |
| **Release maturity** | V1.0.1. Expect device-specific camera quirks and Wi-Fi-dependent latency. |

---

## Features

### Android App

| Feature | Details |
|---|---|
| **Full HD Streaming** | Streams a 1080p camera feed at up to 60 fps over SRT. |
| **Hardware Encoding** | Uses MediaCodec HEVC/H.265 with H.264 fallback. |
| **Audio Streaming** | Sends microphone audio with the video stream as AAC. |
| **Multi-Lens Switching** | Supports rear, ultrawide, telephoto, and front cameras when available. |
| **Pinch-to-Zoom** | Smooth digital zoom with a live zoom indicator. |
| **Screen-Off Streaming Mode** | Dims the phone to a black overlay while keeping capture and streaming active. |
| **Torch and Screen Controls** | Keeps the phone awake and can toggle torch while streaming. |
| **OBS Slot Picker** | Shows available OBS camera slots on the phone and blocks busy slots from accidental reuse. |
| **Auto-Discovery** | Finds OpenStream OBS listeners on the same LAN and advertises the phone back to OBS. |
| **Manual Connect** | Supports manual IP and port entry when discovery is blocked. |
| **Live Stream Telemetry** | Shows frames, keyframes, transferred megabits, errors, and active lens while streaming. |
| **Identify Overlay** | Displays a large slot label on the phone when triggered from OBS. |
| **Reconnect Hold** | Keeps a reserved OBS slot for `45` seconds after a disconnect to make brief Wi-Fi drops less disruptive. |

### OBS Plugin

| Feature | Details |
|---|---|
| **Native OBS Source** | Adds an `OpenStream V8` source type inside OBS Studio while preserving V7 scene compatibility. |
| **Multi-Camera Dock** | Shows every OpenStream source with state-aware connect, retry, stop, discovery, and live camera controls. |
| **One-Click Installer** | Windows installer copies the plugin into the OBS plugin folder. |
| **Camera Slots** | Gives each source a stable slot label such as `CAM A`, `CAM B`, or a custom slot name. |
| **Phone Discovery** | Lists discovered Android phones, includes a refresh action, and can let the phone choose the OBS slot. |
| **Auto-Connect** | Listens for the Android app and connects without typing IP addresses. |
| **Deep-Link Pairing URL** | Exposes an `openstream://connect` pairing URL with slot, port, latency, and source identity. |
| **Separate Audio Mixer** | Phone microphone audio gets its own OBS mixer channel. |
| **Remote Controls** | Adjust zoom, torch, rear/front camera, and the phone slot label overlay from the persistent dock. |
| **Reconnect Handling** | Reserves and releases phones per source so streams recover into the same OBS slot. |

---

## UX Flow

| Moment | Experience |
|---|---|
| **First launch** | The phone explains camera/microphone access, then presents a guided discovery state over the camera preview. |
| **OBS setup** | Add one `OpenStream V8` source per camera angle, name the production slots, and monitor them from the Camera Control dock. |
| **Pairing** | The phone scans for available OBS slots and provides refresh, empty-state recovery, and manual fallback. |
| **Going live** | A `LIVE` badge, zoom chip, stream stats, and status text make the active connection visible at a glance. |
| **Multi-camera work** | Busy and reserved slots prevent two phones from fighting over the same source. |
| **On-set checks** | Use `Show Slot Label on Phone` in OBS to flash the slot label on the physical phone. |
| **Battery and heat** | Use `DISPLAY` for a black screen-off overlay or `STAY` to keep the phone awake. |

---

## Architecture

```text
Android phone
  Camera2 preview/capture
  MediaCodec HEVC/H.264 video
  MediaCodec AAC audio
  MPEG-TS muxer
  libsrt sender
        |
        | SRT media stream on port 9000
        v
Windows PC
  OBS Studio
  OpenStream native source plugin
  FFmpeg SRT receive/decode
  OBS video frame + audio mixer output
```

Discovery uses UDP port `51515`. Camera remote controls use the phone HTTP control server on port `9001`.

### Transport Defaults

| Parameter | Default | Notes |
|---|---|---|
| Resolution | `1920x1080` | Full HD capture target |
| Frame rate | `60 fps` | 30-60 fps depending on device support |
| Video codec | HEVC/H.265 | H.264 fallback |
| Bitrate | `50 Mbps` | High-quality local Wi-Fi default; lower if the link drops frames |
| SRT latency | `120 ms` | 80-200 ms useful range |
| Discovery port | `51515/udp` | LAN discovery beacon |
| SRT port | `9000` | Media stream |
| Control port | `9001/http` | Remote zoom, torch, lens |

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
