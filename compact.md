# OpenStream Conversation Compact

## User Goal

Build **OpenStream**, an open-source wireless Android camera system for OBS. The original vision was raw Camera2 YUV -> zstd lossless -> SRT -> Windows -> OBS, but after review the project was reframed into a practical prototype-first roadmap:

- Android captures camera with Camera2.
- Android uses hardware `MediaCodec` HEVC/H.265 by default, H.264 fallback.
- Android sends over SRT later.
- Windows/OBS receives through FFmpeg/libsrt and a native OBS source plugin later.
- Lossless raw YUV/zstd remains a research track after reliable streaming works.

## Repo / Environment

- Workspace: `C:\Users\YashasVM\Downloads\code\OBS`
- GitHub repo: `https://github.com/YashasVM/OpenStream`
- Branch: `main`
- Current pushed commit: `b61e145 obs: add native source plugin scaffold`
- User is on Windows 11.
- Android SDK exists at: `C:\Users\YashasVM\AppData\Local\Android\Sdk`
- Android Studio bundled JBR exists at: `C:\Program Files\Android\Android Studio\jbr`
- FFmpeg exists locally and supports SRT.
- `scrcpy` 4.0 is installed.

## Implemented In Repo

Created a greenfield scaffold:

- `README.md`
- `.gitignore`
- `docs/architecture.md`
- `docs/setup.md`
- `docs/testing.md`
- `docs/protocol.md`
- `tools/openstream_receiver.py`
- `tests/test_repo_contract.py`
- `android/` Android Gradle project
- `obs-plugin/` native OBS plugin scaffold

Android scaffold includes:

- `MainActivity.kt`: simple UI with preview, SRT URL field, Start/Stop buttons.
- `Camera2Controller.kt`: Camera2 preview + encoder surface session.
- `MediaCodecVideoEncoder.kt`: HEVC-first/H.264 fallback encoder scaffold.
- `StreamConfig.kt`: `Default1080p30` and `Fallback720p30` presets.
- `SrtStreamClient.kt`: Kotlin boundary to native SRT bridge.
- `TelemetrySampler.kt`: battery, Wi-Fi RSSI, device, codec, bitrate telemetry.
- `openstream_srt.cpp`: JNI native bridge placeholder. It logs encoded access units but does not send real SRT yet.

OBS scaffold includes:

- `obs-plugin/src/openstream-source.cpp`
- Registers `openstream_source`
- Exposes device name, SRT URL, latency, bitrate, connect/disconnect controls
- Does not yet decode video or output frames to OBS.

Windows receiver:

- `tools/openstream_receiver.py`
- Checks FFmpeg SRT support.
- Can listen on `srt://0.0.0.0:9000?mode=listener&latency=120`
- Useful once Android sends a real SRT/MPEG-TS stream.

## Build Fixes Already Made

The Android project initially failed in Android Studio due to:

1. Java 17 toolchain discovery issue.
2. Deprecated/unresolved `kotlinOptions`.
3. Gradle 9.0.0 / Android Gradle Plugin 8.7.3 incompatibility.
4. Kotlin layout compile error.

Fixes applied:

- Removed `kotlin { jvmToolchain(17) }`.
- Added Java 17 compile options and Kotlin `compilerOptions`.
- Pinned Gradle wrapper to `8.9`.
- Fixed `addView` call in `MainActivity.kt`.

Confirmed command-line Android build succeeds with:

```powershell
cd C:\Users\YashasVM\Downloads\code\OBS\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

Result was `BUILD SUCCESSFUL`.

APK path:

```text
C:\Users\YashasVM\Downloads\code\OBS\android\app\build\outputs\apk\debug\app-debug.apk
```

Python contract tests pass:

```powershell
python -m pytest tests
```

Result: `5 passed`.

## Git Push History

Remote was added:

```text
origin https://github.com/YashasVM/OpenStream
```

Branch renamed to `main`.

Pushed commits:

```text
7f0b47d docs: define OpenStream prototype architecture
28dc884 tools: add SRT receiver smoke test
76ad7f3 android: scaffold Camera2 streaming prototype
b61e145 obs: add native source plugin scaffold
```

Important: `compact.md` should stay local and should not be pushed.

## Current Reality / Honest Status

The OpenStream app currently shows camera preview and says "Streaming", but it does not yet send a playable SRT stream. The native SRT bridge is a placeholder that logs access units.

Therefore:

- OBS cannot currently ingest OpenStream directly.
- The Python SRT receiver/OBS SRT Media Source will not show phone video yet.
- Next real implementation step is to link Android `libsrt` and mux `MediaCodec` output into an FFmpeg-readable stream, likely MPEG-TS over SRT.

## User Got Wireless OBS Working Today

Because OpenStream direct SRT is not ready, the user wanted a working wireless feed immediately. We used wireless `scrcpy`.

User phone IP:

```text
192.168.1.6
```

Windows machine Wi-Fi IP:

```text
192.168.1.2
```

ADB path:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

Wireless ADB was enabled successfully:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" start-server
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" tcpip 5555
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.6:5555
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Successful device output:

```text
192.168.1.6:5555        device
```

Then user can run:

```powershell
scrcpy --tcpip=192.168.1.6:5555 --video-bit-rate 16M --max-fps 60 --max-size 1920 --stay-awake
```

This opens a smooth wireless phone mirror. User confirmed it still works after unplugging USB.

OBS workflow:

- OBS -> Sources -> `+` -> Window Capture
- Select `scrcpy` window
- Open phone camera app or OpenStream preview
- Crop/resize in OBS

Current working path:

```text
Phone camera preview
-> phone screen capture
-> scrcpy stream over wireless ADB
-> scrcpy window on Windows
-> OBS Window Capture
```

To stop wireless ADB:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" disconnect 192.168.1.6:5555
```

or restart the phone.

## Next Technical Steps For OpenStream

Highest priority:

1. Replace Android native SRT placeholder with real transport.
2. Decide easiest first real stream format:
   - Recommended: MPEG-TS muxed HEVC/H.264 over SRT so FFmpeg/OBS can read it.
3. Add native or Java/Kotlin muxing path:
   - Feed `MediaCodec` encoded access units to a muxer.
   - Send muxed stream to SRT socket.
4. Validate with:

```powershell
python tools\openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

5. Then test OBS Media Source:

```text
srt://0.0.0.0:9000?mode=listener&latency=120
```

6. Only after OBS Media Source works, continue native OBS plugin decode/render work.

## Important User Preference / Tone

User is impatient and wants practical results fast. They were frustrated that OpenStream did not immediately work in OBS. Be direct and honest. Do not imply the SRT path is working until it really is. Prefer giving commands that work now.

