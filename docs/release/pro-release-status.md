# OpenStream pro release implementation

Prepared worktree: `/home/yvm/.t3/worktrees/OpenStream/t3-b4d0d0c1-10aaac058ee284b0`
Branch: `t3/openstream-pro-camera-adaptive-streaming`, based on `78abe6b`.
Original checkout and its untracked screen-share directory were not edited.

## Implemented

- Camera2 supported EV compensation, AE/AWB/AF locks, preview tap focus with orientation/mirror/crop mapping, manual shutter/ISO/focus, white balance presets and reset. Capability and requested state are available to the phone UI and authenticated OBS control API/dock. Locks report requested settings, not measured convergence.
- Phone camera control panel extends the existing rounded pill/glass UI. Parent and the named alternate branch share these committed assets; no alternate checkout was changed.
- Real cumulative SRT RTT/loss/retransmit/send-buffer telemetry with transport generation checks. Adaptive bitrate reduces after sustained congestion, recovers more slowly and changes the running hardware encoder without replacing surfaces or timestamps. Settings expose opt-out and live bitrate/RTT/queue feedback. Codec rejection disables adaptation for that session.
- Native screen source switches an already connected OBS stream from camera to MediaProjection after explicit consent. A foreground projection/microphone service keeps it alive when another app is visible. Notification Stop, revocation and service disconnection release projection and stop the stream. A lost screen stream requires fresh consent rather than silently opening a hidden camera.
- Actual SRT 1.5.7 static libraries for all four ABIs, public headers and a checksum/commit-pinned rebuild script. Encryption remains disabled as in the original bundle.
- Existing build components updated to compatible AGP 8.13.2, Gradle 8.13, Kotlin 2.3.21 and app NDK 28.2.13676358. Application ID, release version and signing requirements are unchanged.

## Incomplete release requirements

- 4K SDR is not enabled. USB route evidence, camera/hardware encoder capability helpers and native exact-local-address binding/verification exist, but the current OBS reservation protocol does not negotiate receiver capability. No high mode is exposed that could silently fall back to Wi-Fi. Settings explain the disabled reason. Wiring the route monitor into a future high-mode session remains required.
- HDR is not implemented. The pipeline remains 8-bit AVC/SDR. It lacks a complete ten-bit camera session, Main10 encode, mux color signalling and HDR OBS render path. HDR is explicitly unavailable, never simulated with a flag.
- Screen sharing audio is microphone or muted. Android playback-audio capture is not implemented; the phone explains this before consent. Protected content remains subject to Android capture restrictions.
- Screen source selection currently requires an existing OBS stream. Projection consent must be granted again after interruption. Rotation uses the fixed stream dimensions and Android's virtual-display fitting.

## Verification

- Python repository/native contracts: 118 passed, including a real local SRT transfer with telemetry and local-address rejection.
- OBS plugin build and CTest: 4/4 passed.
- Android JVM tests: 38 passed. Lint, four-ABI native debug build and APK assembly passed. Lint retains existing warnings; no lint baseline was added.
- APK 16 KB ELF and ZIP alignment checked with `tools/check_android_16kb_alignment.py`.
- Redmi Note 8 was discovered through device tools. The prescribed open and one wait retry both returned DEVICE_IN_USE. Another session was not reused or closed. No hardware behavior is claimed validated.

## Primary references

- [Camera request controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- [MediaProjection lifecycle and consent](https://developer.android.com/media/grow/media-projection)
- [USB network transport](https://developer.android.com/reference/android/net/NetworkCapabilities#TRANSPORT_USB)
- [HDR camera requirements](https://developer.android.com/media/camera/camera2/hdr-video-capture)
- [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [SRT 1.5.7 release](https://github.com/Haivision/srt/releases/tag/v1.5.7)
- [SRT rebuild details](../../android/app/src/main/cpp/third_party/srt/README.md)
