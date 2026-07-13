<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
  <img alt="OpenStream Banner" src="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
</picture>

### Turn any Android phone into a wireless camera source for OBS Studio

[![Status](https://img.shields.io/badge/status-beta-orange?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream/releases)
[![Release](https://img.shields.io/github/v/release/YashasVM/OpenStream?display_name=tag&style=flat-square&labelColor=1a1a2e&color=00D4AA)](https://github.com/YashasVM/OpenStream/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows-green?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![OBS](https://img.shields.io/badge/OBS-Studio%20Plugin-purple?style=flat-square&labelColor=1a1a2e)](https://obsproject.com)
[![Website](https://img.shields.io/badge/website-openstream.pages.dev-00D4AA?style=flat-square&labelColor=1a1a2e)](https://openstream.pages.dev)

**Open-source** | **Low-latency** | **Hardware-accelerated** | **Local Wi-Fi**

---

</div>

> [!IMPORTANT]
> OpenStream V2 is a beta release. It is ready for local Wi-Fi camera workflows, but device-specific camera behavior and network quality can still vary. Please report bugs in [GitHub Issues](https://github.com/YashasVM/OpenStream/issues).

**[Download](#quick-downloads)** · **[Quick Start](#quick-start)** · **[Features](#features)** · **[Build](#building)** · **[Release Guide](docs/release.md)**

## Quick Downloads

| Step | Download | Install on |
|---|---|---|
| 1 | [`openstream-android.apk`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-android.apk) | Android phone |
| 2 | [`openstream-obs-plugin-installer-windows-x64.exe`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-plugin-installer-windows-x64.exe) | Windows OBS PC |
| Fallback | [`openstream-obs-windows-x64.zip`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-windows-x64.zip) | Manual OBS plugin install |

Need the non-technical walkthrough with screenshots? Start with [`docs/set-up.md`](docs/set-up.md).

---

## What is OpenStream?

OpenStream sends your Android phone camera directly into OBS Studio over local Wi-Fi. It uses Camera2, MediaCodec video/audio encoding, MPEG-TS muxing, SRT transport, two-way LAN discovery, source-slot pairing, and a native OBS source plugin.

```text
Phone camera -> HEVC/H.264 + AAC -> SRT over Wi-Fi -> OpenStream camera slot in OBS
```

### Why OpenStream V2

| Area | What you get |
|---|---|
| **OBS-native workflow** | A dockable Camera Control panel keeps connection, lens, torch, smooth live zoom, and identify controls visible while you work. |
| **Low-latency media** | Hardware video/audio encoding, SRT transport, bounded buffering, and stale-frame dropping keep the preview responsive. |
| **Reliable pairing** | LAN discovery, stable camera slots, reservation handling, and fast reconnects keep phones attached to the right OBS source. |
| **Upgrade safety** | Existing scene sources keep loading while new sources appear simply as `OpenStream`. |
| **Clean delivery** | A signed Android release path, Windows installer, checksums, and update manifests keep upgrades consistent. |

OpenStream is built for creators who want an inspectable, open-source, OBS-first phone camera pipeline without a separate desktop webcam client.

---

## Quick Start

### 1. Install the Android App

Download the APK from the release, copy it to your Android phone, open it, and allow the install prompt. Grant camera and microphone permissions on first launch.

### 2. Install the OBS Plugin

Close OBS, then download and run `openstream-obs-plugin-installer-windows-x64.exe`. One Windows administrator prompt lets it replace old system/user copies, keep one canonical plugin, and support clean upgrade, repair, and uninstall. Start OBS again after it finishes.

> [!NOTE]
> If you prefer manual installation, download `openstream-obs-windows-x64.zip`, extract it, close OBS, and run `install-openstream-plugin.bat`.

### 3. Add Camera Slots in OBS

In OBS, click `+` in Sources, choose `OpenStream`, keep the camera slot enabled, and press OK. Each source gets a slot such as `CAM A` or `CAM B`, so multi-phone setups can stay organized.

### 4. Pair the Phone

Open the OpenStream Android app on the same Wi-Fi network as the PC. Tap an available OBS camera slot from the phone, or choose a discovered phone from the OBS source properties. The camera feed should appear in OBS within a few seconds.

### 5. Stream

Use OBS as usual. Open `View > Docks > OpenStream Camera Control` to keep connection, lens, torch, zoom, and identify controls beside your preview. Phone audio appears as a separate OBS mixer channel, and the selected phone is held for quick reconnects if Wi-Fi drops briefly.

> [!TIP]
> Use a 5 GHz or Wi-Fi 6 network, keep both devices on the same subnet, and disable VPNs or router client isolation during first setup.

---

## Compatibility & Limits

| Area | Current support |
|---|---|
| **Phone OS** | Android 10+ with Camera2 and hardware MediaCodec support. |
| **OBS host** | Windows x64 with OBS Studio and FFmpeg SRT support. |
| **Network** | Same LAN/subnet; guest Wi-Fi, VPNs, and client isolation can block discovery. |
| **Release maturity** | Beta. Expect device-specific camera quirks and Wi-Fi-dependent latency. |

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
| **Native OBS Source** | Adds an `OpenStream` source type inside OBS Studio while preserving existing scenes. |
| **One-Click Installer** | Windows installer removes stale legacy copies and supports clean install, upgrade, repair, and uninstall. |
| **Camera Slots** | Gives each source a stable slot label such as `CAM A`, `CAM B`, or a custom slot name. |
| **Phone Discovery** | Lists discovered Android phones, includes a refresh action, and can let the phone choose the OBS slot. |
| **Auto-Connect** | Listens for the Android app and connects without typing IP addresses. |
| **Deep-Link Pairing URL** | Exposes an `openstream://connect` pairing URL with slot, port, latency, and source identity. |
| **Separate Audio Mixer** | Phone microphone audio gets its own OBS mixer channel. |
| **Real-Time Remote Controls** | Adjust smooth zoom while dragging, toggle the torch, switch rear/front cameras, and show the phone slot label from OBS. |
| **Dockable Control Panel** | Control any OpenStream source from an always-available native OBS dock instead of reopening source properties. |
| **Update Notices** | Checks the latest release metadata and links to the installer when a newer plugin is available; the loaded DLL is never replaced in place. |
| **Reconnect Handling** | Reserves and releases phones per source so streams recover into the same OBS slot. |

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
| Bitrate | `16 Mbps` | High-quality HEVC default with headroom for Wi-Fi retransmits |
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

## Roadmap

- [x] Camera2 hardware-accelerated capture
- [x] HEVC/H.265 and H.264 encoding with MediaCodec
- [x] SRT transport with MPEG-TS muxing
- [x] Native OBS source plugin with FFmpeg decode
- [x] LAN auto-discovery
- [x] Audio streaming into the OBS mixer
- [x] Remote camera controls from OBS
- [x] Windows plugin installer
- [ ] Adaptive bitrate based on network conditions
- [ ] QR pairing for restricted networks
- [ ] GPU zero-copy receive path
- [ ] macOS/Linux OBS plugin packages

---

## Links

| Link | URL |
|---|---|
| Website | [OpenStream.pages.dev](https://openstream.pages.dev) |
| Releases | [github.com/YashasVM/OpenStream/releases](https://github.com/YashasVM/OpenStream/releases) |
| Issues | [github.com/YashasVM/OpenStream/issues](https://github.com/YashasVM/OpenStream/issues) |

---

<div align="center">

**Made by [@YashasVM](https://github.com/YashasVM)**

*Turn your phone into a pro OBS camera source. No cables required.*

</div>
