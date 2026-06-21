<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
  <img alt="OpenStream Banner" src="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
</picture>

### Turn any Android phone into a wireless camera source for OBS Studio

[![Status](https://img.shields.io/badge/status-beta-orange?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows-green?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![OBS](https://img.shields.io/badge/OBS-Studio%20Plugin-purple?style=flat-square&labelColor=1a1a2e)](https://obsproject.com)
[![Website](https://img.shields.io/badge/website-openstream.pages.dev-00D4AA?style=flat-square&labelColor=1a1a2e)](https://openstream.pages.dev)

**Open-source** | **Low-latency** | **Hardware-accelerated** | **Local Wi-Fi**

---

</div>

> [!IMPORTANT]
> OpenStream is a beta project. It works today, but expect rough edges while the Android app and OBS plugin mature. Please report bugs in [GitHub Issues](https://github.com/YashasVM/OpenStream/issues).

## Quick Downloads

| Need | Download |
|---|---|
| Android app | [`openstream-android-debug.apk`](https://github.com/YashasVM/OpenStream/releases/download/v0.1.1-beta/openstream-android-debug.apk) |
| OBS plugin installer for Windows | [`openstream-obs-plugin-installer-windows-x64.exe`](https://github.com/YashasVM/OpenStream/releases/download/v0.1.1-beta/openstream-obs-plugin-installer-windows-x64.exe) |
| Manual OBS plugin zip | [`openstream-obs-windows-x64.zip`](https://github.com/YashasVM/OpenStream/releases/download/v0.1.1-beta/openstream-obs-windows-x64.zip) |

Need the non-technical walkthrough with screenshots? Start with [`docs/set-up.md`](docs/set-up.md).

---

## What is OpenStream?

OpenStream sends your Android phone camera directly into OBS Studio over local Wi-Fi. It uses Camera2, MediaCodec video/audio encoding, MPEG-TS muxing, SRT transport, LAN discovery, and a native OBS source plugin.

```text
Phone camera -> HEVC/H.264 + AAC -> SRT over Wi-Fi -> OpenStream source in OBS
```

---

## Quick Start

### 1. Install the Android App

Download the APK from the release, copy it to your Android phone, open it, and allow the install prompt. Grant camera and microphone permissions on first launch.

### 2. Install the OBS Plugin

Download and run `openstream-obs-plugin-installer-windows-x64.exe` on the Windows PC that has OBS Studio installed. Restart OBS after the installer finishes.

> [!NOTE]
> If you prefer manual installation, download `openstream-obs-windows-x64.zip`, extract it, and run `install-openstream-plugin.bat` as administrator.

### 3. Add the Source in OBS

In OBS, click `+` in Sources, choose `OpenStream`, keep auto-connect enabled, and press OK. The source waits for a phone.

### 4. Connect the Phone

Open the OpenStream Android app on the same Wi-Fi network as the PC. Tap the discovered OBS device. The camera feed should appear in OBS within a few seconds.

### 5. Stream

Use OBS as usual. Phone audio appears as a separate OBS mixer channel, and camera controls are available from the OpenStream source properties.

> [!TIP]
> Use a 5 GHz or Wi-Fi 6 network, keep both devices on the same subnet, and disable VPNs or router client isolation during first setup.

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
| **Torch and Screen Controls** | Keeps the phone awake and can toggle torch while streaming. |
| **Auto-Discovery** | Finds OpenStream OBS listeners on the same LAN. |
| **Manual Connect** | Supports manual IP and port entry when discovery is blocked. |

### OBS Plugin

| Feature | Details |
|---|---|
| **Native OBS Source** | Adds an `OpenStream` source type inside OBS Studio. |
| **One-Click Installer** | Windows installer copies the plugin into the OBS plugin folder. |
| **Auto-Connect** | Listens for the Android app and connects without typing IP addresses. |
| **Separate Audio Mixer** | Phone microphone audio gets its own OBS mixer channel. |
| **Remote Controls** | Adjust zoom, torch, and camera selection from source properties. |
| **Reconnect Handling** | Reconnects when the phone stream drops or restarts. |

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
| Bitrate | `20 Mbps` | Tuned for local Wi-Fi |
| SRT latency | `120 ms` | 80-200 ms useful range |
| Discovery port | `51515/udp` | LAN discovery beacon |
| SRT port | `9000` | Media stream |
| Control port | `9001/http` | Remote zoom, torch, lens |

---

## Building

### Android App

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> Normal APK builds link bundled libsrt static libraries for real network streaming. Use `-Popenstream.nonStreamingCiBuild=true` only for intentional source-compile checks.

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
| `openstream-android-debug.apk` | Android app install package |
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
