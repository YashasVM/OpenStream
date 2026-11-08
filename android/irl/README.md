# OpenStream-IRL Android

Separate app `dev.openstream.irl`, labelled **OpenStream-IRL**. It installs alongside OpenStream and contains camera/encoder source snapshots so the original app can evolve independently. The app uploads over HTTPS; the self-hosted deployment uses a separate Tailscale connection to reach the relay. No streaming-provider credentials are needed.

Build from the repository root:

```sh
tools/build_irl_apk.sh
```

The script respects `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT`. Otherwise it discovers Java on PATH and uses `$HOME/Android/Sdk`. Install SDK platform 35, NDK 27.1.12297006, and CMake 3.22.1. Targeted Gradle configuration avoids configuring the original `:app` module. Output:

```
android/irl/build/outputs/apk/debug/irl-debug.apk
```

The script runs the module's JVM checks, Android lint including API compatibility, and assembles the APK. Optional host-only packetizer verification requires g++, ffmpeg and ffprobe:

```sh
python3 android/irl/src/test/native/mux_check.py
python3 tools/check_android_16kb_alignment.py android/irl/build/outputs/apk/debug/irl-debug.apk
```

## Pair and capture

Create a session in the separately managed relay UI. Open its pairing link on Android, or paste the full link into the app's private field and tap **Save pairing**:

```
openstreamirl://pair?relay=ENCODED_HTTPS_ORIGIN&session=UUID&token=PUBLISH_TOKEN
```

Relay must be an HTTPS origin, without a path, user information, query, or fragment. Tokens accept 43-character base64url or 64-character hex encodings of 256 bits. Credentials stay in private app storage, Android backup is disabled, and the app does not log them. Pairing cannot change while an earlier run is pending. The app opens no viewer URL and handles no viewer token.

Select 1–12 Mbps, default 4, then **Start capture**. Grant camera and microphone permissions when prompted. Encoding uses hardware H.264 at 1280×720, 30 FPS, one-second keyframes and mono 48 kHz AAC. Torch, front/back, and zoom use camera hardware support. Keep the screen open while filming.

Start calls `POST /api/sessions/{sessionId}/start` with bearer publish authentication. It validates the returned UUID run ID. Each independently decodable TS segment starts with PAT/PMT and a video keyframe with SPS/PPS. Audio/video frames are interleaved by presentation time before segment boundaries. Video timestamps are anchored to the phone's monotonic clock at the first encoded frame to accommodate HALs with a different timestamp origin. AAC config comes from the encoder.

Closed chunks and duration/capture-time metadata publish together by atomic directory rename. The queue retries sequential `PUT /api/sessions/{sessionId}/runs/{runId}/segments/{sequence}`, starting at zero, with `Content-Type: video/mp2t`, `X-Duration-Ms`, `X-Capture-Time-Ms`, and bearer publish authentication. Uploads have 8-second connection and 12-second read timeouts plus a 30-second overall disconnect deadline. Redirects are refused. New requests use the current default Android network route. Lost responses retry the same run/sequence. The uploader deletes a local chunk only after `nextSequence == sequence + 1`. It drains up to eight chunks or five seconds per burst and immediately continues while a backlog remains. Connection failures wait two seconds before retrying.

Stop finishes the current segment and marks capture ended locally. Once the queue drains, `POST /api/sessions/{sessionId}/end` finishes the playlist. A process restart drains the previous run before allowing another run. The relay contract must preserve the prior run's playlist with a discontinuity. Nothing here implements or deploys the separately managed Worker, Durable Object, R2 retention, or browser receiver.

## Current limits

- The UI is portrait. Encoded video uses the camera sensor's native 1280×720 orientation, with no pixel rotation or front-camera mirroring pass. Holding the phone portrait can therefore produce sideways video. Correct portrait pixels would need a GPU rotation stage. Verify framing on the user's phone before use.
- There is no foreground service. Leaving the screen stops capture. Uploading may continue while Android retains the process; reopening the app resumes committed chunks. Capture does not automatically resume after process death.
- A new run needs a reachable relay. Short outages after start queue on disk. At 256 MiB or 120 seconds of queued media, capture stops with a visible error; earlier committed chunks remain. There is no silent eviction, queue-discard button, or automatic bitrate adaptation. An unfinished segment can be lost on process death, storage failure, or a full queue.
- HTTP 4xx errors other than 408/429 stop capture and retain the queue. **Retry pending uploads** retries that same run. A server-expired or superseded run needs operator resolution; the app never sends its queued chunks into a new run.
- Hardware must support the fixed 720p30 CBR AVC profile at the selected bitrate. Camera recovery and torch availability depend on the vendor HAL. A/V clock anchoring can include initial encoder latency; exact lip-sync and route-switch behavior need user hardware testing.
- No emulator or physical-device testing has been performed. No account was changed and nothing was deployed. The debug APK is not a signed release artifact.
