<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMDBENEFBIiBzdHJva2Utd2lkdGg9IjIiPjxjaXJjbGUgY3g9IjEyIiBjeT0iMTIiIHI9IjEwIi8+PHBhdGggZD0iTTEwIDhsNiA0LTYgNFY4eiIgZmlsbD0iIzAwRDRBQSIvPjwvc3ZnPg==">
  <img alt="OpenStream Banner" src="https://img.shields.io/badge/OpenStream-Turn_Your_Phone_Into_a_Wireless_OBS_Camera-00D4AA?style=for-the-badge&labelColor=0A0A0F">
</picture>

### Turn any Android phone into a wireless camera source for OBS Studio

[![Status](https://img.shields.io/badge/status-beta-orange?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![License](https://img.shields.io/badge/license-MIT%20%2F%20Apache--2.0-blue?style=flat-square&labelColor=1a1a2e)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Windows-green?style=flat-square&labelColor=1a1a2e)](https://github.com/YashasVM/OpenStream)
[![OBS](https://img.shields.io/badge/OBS-Studio%20Plugin-purple?style=flat-square&labelColor=1a1a2e)](https://obsproject.com)

**Open-source** · **Low-latency** · **Hardware-accelerated** · **Zero-config**

---

</div>

> [!IMPORTANT]
> **🚧 Beta Release** OpenStream is under active development. You can use it today, but expect rough edges. [Report bugs](https://github.com/YashasVM/OpenStream/issues) to help shape the FINAL release.

## What is OpenStream?

OpenStream turns your Android phone into a **dedicated wireless camera source** for OBS Studio over local Wi-Fi. No screen mirroring, no capture cards, no cables just your phone's camera feed streamed directly into OBS with hardware-accelerated encoding and sub-200ms latency.

```
Phone Camera  →  HEVC/H.264 Encode  →  SRT over Wi-Fi  → OBS Studio
```

---

## Features

### Android App

| Feature | Details |
|---|---|
| **Full HD Streaming** | 1080p @ 60fps real-time camera feed via SRT protocol |
| **Multi-Lens Switching** | Seamlessly switch between all available lenses — wide, ultrawide, telephoto, front |
| **Pinch-to-Zoom** | Smooth digital zoom with live on-screen indicator |
| **Audio Streaming** | Microphone audio transmitted alongside video in the same SRT stream |
| **Torch Control** | Toggle flashlight on/off during streaming |
| **Keep Screen On** | Prevents display timeout for uninterrupted sessions |
| **Auto-Discovery** | OBS instances on the same network appear automatically — tap to connect |
| **Multi-Cam Setup** | Easily change the streaming port in-app to use multiple phones simultaneously in OBS |
| **Display Off Mode** | Turns screen black and minimizes brightness to save battery/OLED while streaming |
| **Manual Connect** | Fallback IP/port entry for networks that block UDP discovery |

### OBS Plugin

| Feature | Details |
|---|---|
| **Zero-Config Setup** | Add `OpenStream` source → it finds your phone automatically |
| **Separate Audio Mixer** | Phone audio gets its own mixer channel in OBS |
| **Remote Camera Controls** | Control zoom, torch, and lens switching directly from OBS properties |
| **Smooth Zoom Slider** | Live zoom adjustment — auto-applies as you drag |
| **Hardware Decoding** | FFmpeg-backed H.264/HEVC decoding with YUV color metadata |
| **Auto-Reconnect** | Automatically reconnects when the phone stream drops |
| **Made by @yashas.vm** | Credit shown in plugin properties and OBS log |

---

## Quick Start

### 1. Set up OBS

Add `OpenStream` as a source in OBS Studio. Leave **Auto-connect** enabled and click OK. The source will wait for your phone.

### 2. Open the App

Launch OpenStream on your Android phone (**same Wi-Fi network** as your PC). Camera preview starts immediately.

### 3. Connect

Tap the discovered OBS device in the app. Your phone's camera feed appears in OBS within seconds.

```
┌─────────────────────────────────────────────────────────┐
│  OBS Studio                                             │
│  ┌───────────────────────┐                              │
│  │  OpenStream           │ ← Source waits blank         │
│  │  "Auto-connect: ON"   │                              │
│  └───────────────────────┘                              │
│                                                         │
│  Phone joins same Wi-Fi                                 │
│  ↓                                                      │
│  Phone discovers OBS → Tap → Camera feed appears        │
└─────────────────────────────────────────────────────────┘
```

> [!TIP]
> For best results, use a **5 GHz or Wi-Fi 6** network. Keep the phone close to the access point during first tests. Disable VPNs and client isolation.

---

## Architecture

```
┌──────────────────────┐         SRT/MPEG-TS          ┌──────────────────────┐
│     Android Phone    │  ─────────────────────────►  │    OBS Studio (PC)   │
│                      │        Local Wi-Fi           │                      │
│  Camera2 API         │                              │  Native Plugin       │
│  ├─ MediaCodec HEVC  │   ◄─────────────────────     │  ├─ FFmpeg decode    │
│  ├─ MediaCodec AAC   │     HTTP Control Channel     │  ├─ YUV/NV12 output  │
│  ├─ MPEG-TS Muxer    │                              │  ├─ Audio output     │
│  └─ libsrt sender    │                              │  └─ Remote controls  │
│                      │                              │                      │
│  UDP Discovery       │   ◄──────────────────────    │  UDP Discovery       │
│  (Multicast listener)│     Beacon on port 51515     │  (Beacon advertiser) │
│                      │                              │                      │
│  HTTP Control Server │   ◄──────────────────────    │  HTTP Control Client │
│  (port 9001)         │     /zoom /torch /lens       │  (Camera Controls)   │
└──────────────────────┘                              └──────────────────────┘
```

### Transport Defaults

| Parameter | Default | Range |
|---|---|---|
| Resolution | `1920×1080` | — |
| Frame Rate | `60 fps` | 30–60 |
| Video Codec | HEVC/H.265 | H.264 fallback |
| Bitrate | `20 Mbps` | 8–35 Mbps |
| Keyframe Interval | `1 second` | — |
| SRT Latency | `120 ms` | 80–200 ms |
| Discovery Port | `51515` (UDP) | — |
| SRT Port | `9000` | 1024–65535 |
| Control Port | `9001` (HTTP) | — |

---

## Repository Layout

```
android/                     Android Camera2 + MediaCodec streaming app
├── app/src/main/
│   ├── java/dev/openstream/app/
│   │   ├── MainActivity.kt          Full-screen camera UI with controls
│   │   ├── camera/
│   │   │   └── Camera2Controller.kt Multi-lens camera session management
│   │   ├── encoder/
│   │   │   ├── MediaCodecVideoEncoder.kt  HEVC/H.264 hardware encoder
│   │   │   └── MediaCodecAudioEncoder.kt  AAC microphone encoder
│   │   ├── stream/
│   │   │   ├── SrtStreamClient.kt    Native SRT bridge (JNI)
│   │   │   ├── StreamConfig.kt       Resolution/FPS/bitrate presets
│   │   │   └── ConnectionTarget.kt   SRT endpoint model
│   │   ├── discovery/
│   │   │   ├── ObsDiscoveryClient.kt      Listens for OBS beacons
│   │   │   ├── PhoneDiscoveryAdvertiser.kt Advertises phone on LAN
│   │   │   └── DiscoveredObsDevice.kt     Device data model
│   │   ├── control/
│   │   │   └── CameraControlServer.kt HTTP server for remote controls
│   │   └── telemetry/
│   │       └── TelemetrySampler.kt   Battery, RSSI, temp, codec stats
│   └── res/layout/
│       └── activity_main.xml         Cinematic dark-themed camera UI
│
obs-plugin/                  Native OBS Studio source plugin
├── CMakeLists.txt           Build configuration
└── src/
    └── openstream-source.cpp  FFmpeg SRT receive + decode + remote controls
│
tools/
└── openstream_receiver.py   Developer FFmpeg/SRT receiver for smoke tests
│
docs/
├── architecture.md          System design and codec decisions
├── protocol.md              SRT transport and discovery protocol spec
├── setup.md                 Build and installation guide
└── testing.md               Test plan and verification steps
│
tests/
└── test_repo_contract.py    Contract tests for prototype architecture
│
build_plugin.bat             Windows OBS plugin build script
```

---

## Building

### Android App

Open `android/` in Android Studio and build normally, or from command line:

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

> [!NOTE]
> Normal APK builds link bundled libsrt static libraries for real network streaming.
> Use `-Popenstream.nonStreamingCiBuild=true` only for CI source-compile checks that intentionally skip streaming support.

### OBS Plugin

Requires OBS Studio dev headers, CMake, and an FFmpeg build **with SRT protocol support**.

```powershell
# Verify FFmpeg has SRT support
ffmpeg -protocols 2>&1 | Select-String "srt"

# Build the plugin
cmake -S obs-plugin -B build/obs-plugin `
  -DOBS_ROOT="C:/Program Files/obs-studio" `
  -DFFMPEG_ROOT="C:/path/to/ffmpeg-dev"

cmake --build build/obs-plugin --config Release
```

Or use the included build script:

```powershell
.\build_plugin.bat
```

---

## Developer Smoke Test

Validate the SRT transport chain without OBS using the Python receiver:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

This opens an FFmpeg/ffplay window showing the live phone stream. This is a **developer-only** debug tool — normal users should use the OBS plugin directly.

---

## Network Recommendations

- Use a dedicated **5 GHz** or **Wi-Fi 6** network
- Keep the phone close to the access point during first tests
- Disable VPNs and client isolation on your router
- Start with **one phone** before testing multiple devices
- Use fixed bitrate before enabling adaptive behavior

---

## Roadmap

- [x] Camera2 hardware-accelerated capture
- [x] HEVC/H.265 and H.264 encoding with MediaCodec
- [x] SRT transport with MPEG-TS muxing
- [x] Native OBS source plugin with FFmpeg decode
- [x] LAN auto-discovery (UDP multicast beacons)
- [x] Audio streaming (microphone → AAC → OBS mixer)
- [x] Remote camera controls from OBS (zoom, torch, lens)
- [x] Multi-lens switching (wide, ultrawide, telephoto, front)
- [x] Pinch-to-zoom with live indicator
- [x] Keep screen on / torch toggle in app
- [x] Multi-phone support (multiple sources in OBS via port config)
- [ ] Adaptive bitrate based on network conditions
- [ ] QR code pairing for restricted networks
- [ ] GPU zero-copy receive path
- [ ] Raw YUV/zstd lossless transport (research track)

---

## License

OpenStream is intended for **MIT or Apache-2.0** licensing. Final license file will be added before publishing packages or releases.

---

<div align="center">

**Made by [@yashas.vm](https://github.com/YashasVM)**

*Turn your phone into a pro camera source. No cables. No compromises.*

</div>
