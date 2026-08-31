# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and any unexpected reservation that lacks a bound controller now fails closed instead of allowing a LAN client to claim or release it.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for every mutating route before body parsing/dispatch.
- Added `tools/srt_timeout_probe.py`, a real FFmpeg/libSRT fault-injection probe for receiver timeout calibration, including temporary-blackhole recovery testing.
- Wired the measured receiver policy into the OBS FFmpeg SRT input: 4.5 s I/O timeout plus 2 s connection timeout, before `avformat_open_input`, with a repository contract test locking the values and placement.

## In progress

- Validate the new OBS SRT timeout on the exact `agent-dev` head. Synthetic testing supports the 4.5 s budget, but current-head Android/Windows CI and physical phone/Wi-Fi behavior still need confirmation before calling the runtime change stable.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect/control behavior, sustained latency, A/V sync, thermals and Virtual Camera startup.

## Tests performed

- Android APK and Windows OBS CI are both green on exact pre-runtime-change head `37abdf7`.
- Latest CodeRabbit review before the runtime timeout change found no new actionable correctness/security issue.
- `python -m py_compile` passed for the temporary-blackhole probe logic in the available environment, using FFmpeg with SRT support.
- Temporary-blackhole recovery measurements with `timeout=4,500,000 us`: 2.0 s outage recovered in 566 ms after restore; 3.0 s in 709 ms; 3.5 s in 612 ms; 4.0 s in 610 ms. These are synthetic loopback/libSRT measurements, not physical Wi-Fi results.
- Current-head CI is pending after the OBS timeout wiring and contract test.

## Benchmarks

- Hard blackhole, `timeout=1,500,000 us`: receiver exit in 1,759.3 ms.
- Hard blackhole, selected `timeout=4,500,000 us`: receiver exit in 4,777.4 ms, 4,807.0 ms and 4,772.2 ms across three runs.
- Temporary blackholes with the same 4.5 s timeout recovered without receiver exit at 2.0 s, 3.0 s, 3.5 s and 4.0 s outages; post-restore media-flow recovery was 566–709 ms.

## Known problems / regressions

- No known regression from the runtime SRT timeout is established yet, but exact-head CI and physical phone/Wi-Fi testing are still pending.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- No unresolved CodeRabbit correctness/security blocker is currently identified from the previous reviewed head; the new runtime timeout change still needs fresh review/CI.
- Approval is intentionally withheld because the long-running PR remains draft and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify native OBS controls continue to work while browser-origin requests cannot mutate the phone.
- Verify the OBS-side 4.5 s SRT timeout exits a real phone stream blackhole near the synthetic ~4.8 s result, re-enters the existing 500 ms reconnect loop, and does not force reconnects during shorter Wi-Fi interruptions.