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

## USB 4K and HDR implementation

Implemented in `969d924`, `8dcc38f`, `c43c4d5` and `f44ce82` after prepared base `20fdfca`.

- Separate 4K SDR, 1080p HLG10 HDR and 4K HLG10 HDR profiles, all at 30 fps. Settings use existing rounded/glass styling, query the selected camera and hardware encoder, disable unsupported modes and show their reason. HDR requires API 33, Camera2 ten-bit capability, HLG10 and hardware HEVC Main10 with the selected size/rate/bitrate. Redmi Android 11 is correctly HDR-ineligible.
- High modes require an OBS reservation with versioned `receiverCapabilities`, including codec, dimensions, frame rate and dynamic range. Phone replies with exact `session` configuration. Missing receiver capabilities cannot authorize high modes; legacy SDR and 1080p60 still work. Updated OBS checks negotiated high-mode decoded dimensions, codec and HLG transfer.
- USB verification selects a directly attached IPv4 peer. Evidence is Android USB transport, a physical USB sysfs device ancestry, or configured RNDIS/NCM plus a protected system tether broadcast identifying the active gadget interface. Charging and interface names alone never authorize USB. Gadget tethering does not depend on being an upstream ConnectivityManager Network. If Android hides the evidence, high mode remains unavailable with a reason.
- SRT receives a UDP socket pinned with `IP_UNICAST_IF`, bound to the verified local address and acquired through `srt_bind_acquire`. Listener accepts only the reserved peer. This avoids privileged SO_BINDTODEVICE and cannot silently route high-mode packets onto Wi-Fi. The app rechecks the same interface/address/peer before listen, after accept and every listener poll while live. Route loss stops high media; a compatible reservation is required again.
- Camera2 configures the encoder output with HLG10 and feeds its Surface directly. The encoder requires Main10 and BT.2020/HLG/limited-range output metadata, rejecting an 8-bit or metadata downgrade. High modes omit local preview to avoid unsupported dual-output combinations. Camera/encoder failures stop media without silently changing the configured mode to SDR. Selecting an SDR profile is explicit.
- Native TS preserves HEVC VPS/SPS/PPS and stream type 0x24. OBS outputs I010/P010 with HDR color/transfer metadata and refuses HDR through an eight-bit BGRA/I420 fallback. Real generated HEVC HLG and PQ tests prove ten-bit decoded pixel equality and ffprobe color metadata after the actual mux path.

## Remaining release validation and limitations

- Physical end-to-end USB 4K SDR and true camera HDR remain unvalidated because device access is blocked. No phone session was opened, retried, reused or bypassed during this implementation. Verify actual USB tether evidence visibility, Android kernel/netd interface-pinned sockets, negotiated receiver connection, 4K30 camera output and sustained thermal behavior on eligible devices before release.
- HDR also needs an API 33+ ten-bit-capable physical phone and an HDR-configured OBS project/display to validate actual captured highlights/color. Host FFmpeg/OBS tests prove software preservation, not the camera sensor/HAL output on untested hardware.
- High modes use OBS discovery/reservation and the phone SRT listener. Manual caller URLs cannot bypass capability negotiation. High-mode local camera preview is paused; screen sharing requires explicitly selecting SDR.
- Screen sharing audio is microphone or muted. Android playback-audio capture is not implemented; the phone explains this before consent. Protected content remains subject to Android capture restrictions.
- Screen source selection currently requires an existing OBS stream. Projection consent must be granted again after interruption. Rotation uses the fixed stream dimensions and Android's virtual-display fitting.

## Verification

- Python repository/native contracts: 121 passed, including real SRT telemetry, interface pinning versus another live NIC, source/peer rejection, and HEVC HLG/PQ decode/metadata preservation.
- OBS plugin build and CTest: 4/4 passed, including I010/P010 HDR output, eight-bit HDR rejection and receiver negotiation.
- Android JVM tests: 44 passed, including receiver gating, route loss/evidence and Main10 metadata rejection. Lint, four-ABI native debug build and APK assembly passed. Lint retains existing warnings; no lint baseline was added.
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

## USB socket implementation references

- [Linux IP_UNICAST_IF setter](https://github.com/torvalds/linux/blob/v4.9/net/ipv4/ip_sockglue.c#L754-L780) accepts an interface index without CAP_NET_RAW.
- [Linux UDP route construction](https://github.com/torvalds/linux/blob/v4.9/net/ipv4/udp.c#L949-L966) uses the selected interface as the output interface.
- [SRT 1.5.7 socket acquisition](https://github.com/Haivision/srt/blob/v1.5.7/srtcore/channel.cpp) attaches the supplied UDP socket. The implementation does not use privileged SRTO_BINDTODEVICE.
