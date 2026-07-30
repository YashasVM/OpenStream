# OpenStream Beta Test Plan

## Automated gates

Every candidate change must pass:

- `python -m pytest -q`
- Android unit tests and lint
- native-SRT Android debug build
- Windows OBS plugin build
- DLL dependency inspection (`avformat-62`, `avcodec-62`, `avutil-60`, `swscale-9`)
- clean OBS 32.2.1 load smoke test with no OpenStream module-load error
- installer and ZIP migration smoke tests: seed both legacy DLL names, require OBS closed, and assert exactly one canonical DLL plus data directory

Python repository contracts guard architectural boundaries and wire shape.
Kotlin tests own Camera2-independent state, revision, authority, coordinate, and
request-validation logic. C++ tests own control serialization, snapshot
transitions, and source compatibility. Hardware and rendered-UI behavior must
not be replaced by source-text assertions.

## Android camera acceptance

Run on at least one FULL/LEVEL_3 logical multi-camera device, one LIMITED
device, one fixed-focus or otherwise capability-limited device, and the Pixel 8
Pro and Galaxy S24 Ultra certification profiles.

- Capability discovery matches the active camera's Camera2 characteristics;
  unsupported controls are not actionable.
- Changing zoom, torch, focus, ISO, shutter, FPS, stabilization, or white
  balance preserves every unrelated setting in the repeating request.
- Manual exposure clamps ISO and exposure time safely; shutter never exceeds
  the active frame duration.
- Auto, locked, and manual white balance round-trip their applied state.
- AF-C, AF-S, manual focus, fixed focus, AE/AF lock, and failure states display
  accurately for the device.
- Tap-to-focus is accurate after portrait/landscape rotation, front-camera
  mirroring, preview letterboxing, crop, and zoom.
- Continuous zoom uses logical-camera zoom ratios when available and clearly
  reports disruptive physical-camera transitions otherwise.
- Zebra, histogram, peaking, false color, guides, and audio meters never alter
  the encoded image unless explicitly configured as output overlays.
- Program/Preview tally remains legible with the HUD shown or hidden.

## Control-plane and authority acceptance

- Pairing accepts a valid one-time code, stores a long random token, and cannot
  reuse the consumed/expired code.
- Every V2 endpoint except `/v2/pair` rejects a missing, malformed, revoked, or
  unknown bearer token.
- `GET /v2/capabilities` and `GET /v2/state` return the same support and applied
  values used by the Android HUD.
- Each successful mutation increments revision exactly once. A stale
  `expectedRevision` returns `revision_conflict` with current state and does not
  alter Camera2.
- Partial settings patches preserve omitted values; unsupported fields return
  a stable error or explicit skipped result rather than silently succeeding.
- In `collaborative`, interleaved Android and OBS changes serialize and identify
  the actor. In `obs_lock`, local camera controls are read-only while Stop,
  restore display, and request control remain available.
- Only OBS publishes tally. Reconnect restores the current tally rather than a
  stale queued transition.
- A paired V2 client never falls back to V1 after authentication, authority, or
  revision failure.

## Unattended and endurance acceptance

- **Arm for Remote Operation** starts a camera/microphone foreground service
  with a permanent, actionable notification.
- Screen Off preserves camera, audio, encoder, SRT, control, wake-lock, and
  Wi-Fi-lock operation for at least two hours.
- Local emergency Stop ends capture and releases service-owned resources even
  under `obs_lock`.
- Reboot, force-stop, revoked permission, and unarmed process death do not
  attempt an illegal background camera start; OBS shows that physical re-arming
  is required.
- Log battery, temperature, Wi-Fi RSSI, encoder state, frame drops, bitrate,
  A/V drift, and reconnect count throughout the two-hour run.
- Thermal warning and critical states reach both surfaces and recommend or
  apply only the documented degradation policy.

## OBS Control Room acceptance

Test OBS light and dark themes at narrow and wide dock widths, Windows scaling
at 100%, 150%, and 200%, keyboard-only operation, and a screen reader.

- Source Properties contains setup, phone binding, connection actions, and
  collapsed troubleshooting only; live ISO/WB/focus/zoom controls are not
  duplicated there.
- The Control Room roster tracks add, remove, rename, scene collection change,
  reconnect, reservation, and unload without recreating the dock.
- Exposure, Focus, Color, Lens, and Health groups bind to the selected camera's
  capabilities and current revision.
- Preview click-to-focus maps to normalized transmitted-frame coordinates;
  keyboard reticle control reaches the same command path.
- Slider input is coalesced to a bounded command rate and sends the exact final
  value on release. Delayed commands cannot overwrite a newer revision.
- Disruptive FPS/codec/resolution/lens changes show **Apply & Reconnect**, warn
  on Program, and recover on a fresh keyframe.
- Multi-camera preset preview reports skipped unsupported settings before apply.
- Status, progress, warning, tally, focus state, and errors never rely on color
  alone. Focus order remains stable while state changes.

## Media, network, and recovery acceptance

- OBS receives the phone camera feed (never the Android screen) and mono AAC at
  48 kHz on the source mixer channel.
- Supported 1080p30/50/59.94/60 profiles run for 30 minutes without receiver
  crash; each advertised fractional rate is verified as a rational rate.
- Live-safe camera controls do not interrupt SRT or cause an avoidable keyframe.
- A sub-five-second Wi-Fi interruption reconnects automatically; reservation is
  retained for up to 45 seconds and current state is resynchronized.
- Exercise 1%, 3%, and 5% packet loss, 50 ms jitter, discovery failure, control
  loss with media alive, media loss with control alive, and simultaneous loss.
- Increasing SRT latency from 120 ms to 200 ms improves lossy-network tolerance
  without changing camera-control revisions.

## Compatibility matrix

Before changing discovery, pairing, authentication, reservations, source IDs,
or media framing, test all combinations:

| Android | OBS plugin | Expected result |
|---|---|---|
| Previous | Previous | Existing V1 workflow remains operational |
| Previous | Candidate | Legacy control is labeled and limited; media/scenes still work |
| Candidate | Previous | V1 compatibility works or Android update is withheld |
| Candidate | Candidate | Authenticated V2 workflow and professional controls work end to end |

Also open scene collections containing V7, V8, and legacy OpenStream Beta source IDs
and verify names, phone binding, media, and reconnect settings survive. A
protocol-breaking release is not published unless Android and OBS artifacts
come from the same commit and the entire matrix passes.

## Accessibility and UX acceptance

- Android controls use at least 48 dp targets, TalkBack names include value and
  mode, large text does not obscure tally or emergency Stop, and the HUD can be
  hidden without losing safety state.
- OBS controls have labels, keyboard paths, stable focus, and non-color status;
  unsupported controls explain the device limitation.
- Pending, applied, clamped, stale, unauthorized, and failed commands are
  visually distinct on both surfaces.
- Manual IP/port details remain under Troubleshooting; normal setup uses the
  friendly camera picker and pairing code.

`tools/openstream_receiver.py` remains a developer-only FFmpeg/SRT smoke path,
not part of the operator workflow.
