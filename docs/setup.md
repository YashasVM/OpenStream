# OpenStream Setup

## Requirements

### Android

- Android phone with Camera2 API support
- Android Studio (for building from source)
- Same Wi-Fi network as OBS PC

### OBS Plugin (Windows)

- OBS Studio (with development headers for building)
- FFmpeg with SRT protocol support
- CMake
- Visual Studio or compatible C++ compiler

---

## Android App

### Building

Open `android/` in Android Studio and build normally:

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`

Normal APK builds link the bundled Android ABI-compatible libsrt static
libraries for real network sending. Use `-Popenstream.nonStreamingCiBuild=true`
only for CI source compile checks that intentionally skip streaming support.

### Permissions

The app requires:

- **Camera** — For accessing phone cameras
- **Record Audio** — For microphone streaming
- **Internet** — For SRT network transport
- **Network/Wi-Fi State** — For connection detection
- **Wi-Fi Multicast** — For UDP discovery
- **Foreground Service** — For background streaming
- **Wake Lock** — For keep-screen-on mode

### Using the App

1. Install the APK on your Android phone.
2. Grant camera and microphone permissions when prompted.
3. Camera preview starts immediately.
4. Available OBS listeners appear automatically via LAN discovery.
5. Tap an OBS device to start streaming.

Camera controls available in the app:

- **Lens switching** — Tap lens buttons (1×, 0.5×, 2×, Front) to switch cameras
- **Pinch-to-zoom** — Pinch on the preview to adjust digital zoom
- **Torch** — Toggle flashlight with the 🔦 button
- **Keep screen on** — Tap STAY to prevent display timeout
- **Manual connect** — Tap ⚙ for IP/port entry when discovery is blocked

---

## OBS Plugin

### Building

The plugin is in `obs-plugin/`. Configure with paths to your OBS and FFmpeg
development installations:

```powershell
cmake -S obs-plugin -B build/obs-plugin `
  -DOBS_ROOT="C:/Program Files/obs-studio" `
  -DFFMPEG_ROOT="C:/path/to/ffmpeg-dev"

cmake --build build/obs-plugin --config Release
```

Or use the included build script:

```powershell
.\build_plugin.bat
```

### Verifying FFmpeg SRT Support

The FFmpeg build used by the plugin must include SRT protocol support:

```powershell
ffmpeg -protocols 2>&1 | Select-String "srt"
```

The output must include `srt`.

### Installing

Copy the built plugin DLL to your OBS plugins directory:

```
C:\Program Files\obs-studio\obs-plugins\64bit\
```

### Using the Plugin

1. Open OBS Studio.
2. Add a new source → select **OpenStream**.
3. Keep **Auto-connect discovered phone** enabled.
4. Configure SRT port (default `9000`) and latency (default `120 ms`).
5. Click OK — the source waits blank until a phone connects.
6. Open the Android app on the same Wi-Fi network.
7. Camera feed appears in OBS automatically.

### Camera Remote Controls

With a phone connected, expand the **Camera Remote Controls** group in
the source properties:

| Control | Description |
|---------|-------------|
| **Zoom slider** | Drag to adjust phone zoom (1.0× – 10.0×). Auto-applies live. |
| **Torch ON / OFF** | Toggle the phone's flashlight remotely. |
| **Back Camera** | Switch to rear camera. |
| **Front Camera** | Switch to front-facing camera. |

### OBS Source Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Auto-connect | `true` | Automatically connect to discovered phone |
| Device label | `OpenStream` | Display name for the source |
| Phone SRT port | `9000` | Port for SRT media stream |
| SRT latency | `120 ms` | Buffer latency (80–200 ms range) |
| Expected bitrate | `12 Mbps` | Hint for buffer sizing |

---

## Developer Receiver

For receiver smoke tests without OBS, use the Python tool:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

This is a developer/debug path only; it is not part of the normal user workflow.

---

## Network Recommendations

- Use a dedicated **5 GHz** or **Wi-Fi 6** network
- Keep the phone close to the access point during first tests
- Disable VPNs and client isolation on your router
- Start with one phone before testing multiple devices
- Prefer fixed bitrate before enabling adaptive behavior
- Ensure both devices are on the **same subnet** with UDP multicast enabled
