# OpenStream

> **⚠️ Beta** — This project is under active development. Features may change and bugs may exist.

**Stream your Android phone camera directly into OBS Studio** with ultra-low latency over your local network. No cloud, no subscriptions — just plug-and-play over Wi-Fi.

Made by **[@yashas.vm](https://instagram.com/yashas.vm)**

---

## ✨ Features

### 📱 Android App
- **1080p @ 60fps** real-time camera streaming via SRT protocol
- **Multi-lens switching** — seamlessly switch between all available camera lenses (wide, ultrawide, telephoto, front)
- **Pinch-to-zoom** with smooth digital zoom
- **Torch/flashlight** toggle
- **Keep screen on** mode for uninterrupted streaming
- **Audio streaming** — microphone audio transmitted alongside video
- **Auto-discovery** — OBS finds your phone automatically on the same network

### 🖥️ OBS Plugin
- **Zero-config setup** — add the source and it finds your phone automatically
- **Separate audio mixer channel** — adjust phone audio independently in OBS
- **Remote camera controls** directly from OBS:
  - 🔍 Smooth zoom slider (live preview as you adjust)
  - 💡 Torch ON/OFF
  - 📷 Lens switching (Back / Front camera)
- **H.264 hardware-accelerated** decoding via FFmpeg
- **Auto-reconnect** on disconnect

---

## 📥 Installation

### Android App

1. Download the latest `app-debug.apk` from [Releases](https://github.com/YashasVM/OpenStream/releases)
2. Install on your Android phone (enable "Install from unknown sources" if needed)
3. Grant camera and microphone permissions when prompted

### OBS Plugin (Windows)

1. Download `openstream-obs.dll` from [Releases](https://github.com/YashasVM/OpenStream/releases)
2. Copy it to your OBS plugins directory:
   ```
   C:\ProgramData\obs-studio\plugins\openstream-obs\bin\64bit\
   ```
   Create the folders if they don't exist.
3. You also need the FFmpeg 7 DLLs in the same directory. These are included in the release zip.
4. Restart OBS

---

## 🚀 Usage

1. **Connect both devices** to the same Wi-Fi network
2. **Open the Android app** — it will start the SRT server and broadcast its presence
3. **In OBS**, go to `Sources → + → OpenStream`
4. The plugin will **auto-discover** your phone and start streaming
5. Use the **Camera Remote Controls** in source properties to adjust zoom, toggle torch, or switch lenses

### OBS Source Properties

| Property | Description |
|----------|-------------|
| Auto-connect | Automatically connect to discovered phone |
| Device label | Custom name for this source |
| SRT latency | Adjust latency (80–200ms, default 120ms) |
| Zoom | Smooth zoom control — adjusts live as you drag |
| Torch ON/OFF | Toggle phone flashlight remotely |
| Back/Front Camera | Switch between rear and front camera |

---

## 🛠️ Building from Source

### Android App

```bash
cd android
# Requires Android Studio or Android SDK with NDK installed
./gradlew :app:assembleDebug
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`

### OBS Plugin (Windows)

**Prerequisites:**
- Visual Studio 2022 Build Tools (C++ workload)
- CMake 3.24+
- OBS Studio 32.x installed
- FFmpeg 7 development libraries

```bash
# 1. Set up the Visual Studio environment
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

# 2. Download OBS 32 source headers into obs-plugin/deps/obs-src/
# 3. Download FFmpeg dev libs into obs-plugin/deps/ffmpeg/
# 4. Generate obs.lib from your installed OBS:
dumpbin /exports "C:\Program Files\obs-studio\bin\64bit\obs.dll" > obs_dump.txt
# Parse exports into a .def file, then:
lib /def:obs.def /out:obs-plugin/deps/ffmpeg/lib/obs.lib /machine:x64

# 5. Build
cmake -S obs-plugin -B obs-plugin/build -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release \
  -DOBS_ROOT=obs-plugin/deps/obs-src/libobs -DFFMPEG_ROOT=obs-plugin/deps/ffmpeg
cd obs-plugin/build && nmake
```

---

## 📐 Architecture

```
Phone (Android)                    OBS Studio (Windows)
┌─────────────────┐                ┌─────────────────────┐
│ Camera2 API     │                │ OpenStream Plugin    │
│ ↓               │   SRT/MPEG-TS │ ↓                    │
│ H.264 Encoder   │ ──────────→   │ FFmpeg Demux/Decode  │
│ AAC Encoder     │    Wi-Fi LAN  │ ↓                    │
│ MPEG-TS Muxer   │                │ OBS Video + Audio    │
│                 │                │                      │
│ HTTP Control    │ ←───────────   │ Camera Controls UI   │
│ Server (:9001)  │   HTTP POST   │ (Zoom, Torch, Lens)  │
└─────────────────┘                └─────────────────────┘
       ↕ UDP Beacon (port 51515)
   Auto-Discovery
```

---

## 🤝 Contributing

This project is in **beta**. If you encounter issues or have feature requests, please open an issue on GitHub.

---

## 📄 License

This project is open source. See the repository for license details.
