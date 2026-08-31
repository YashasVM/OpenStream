# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and any unexpected reservation that lacks a bound controller now fails closed instead of allowing a LAN client to claim or release it.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for every mutating route before body parsing/dispatch.
- Added `tools/srt_timeout_probe.py`, a real FFmpeg/libSRT fault-injection probe for receiver timeout calibration.
- Wired the candidate OBS SRT policy into the FFmpeg input: 4.5 s I/O timeout plus 2 s connection timeout, before `avformat_open_input`, with a repository contract test locking the values and placement.
- Corrected the SRT probe after review so the measured receiver now uses production-like SRT `mode=caller` against a synthetic listener and temporary-outage recovery is based on receiver-side FFmpeg frame progress rather than relay UDP packet counts.

## In progress

- Re-run the corrected caller-role SRT probe and decide whether the current 4.5 s runtime timeout is supported by the production-like measurements. Do not treat the earlier listener-role measurements as production validation.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect/control behavior, sustained latency, A/V sync, thermals and Virtual Camera startup.

## Tests performed

- Android APK and Windows OBS CI are both green on exact runtime-timeout head `ddade78`.
- Repository contract tests on that head cover placement of the 4.5 s I/O and 2 s connect timeout before `avformat_open_input`.
- Earlier loopback hard-blackhole calibration measured receiver exits near 4.8 s with a 4.5 s timeout, but those runs used the receiver in listener mode and are retained only as preliminary calibration evidence.
- Earlier temporary-blackhole runs showed transport traffic recovering after 2.0–4.0 s outages, but the old 566–709 ms figures were relay-packet based and are no longer labeled as media-recovery measurements.
- Fresh CI and caller-role probe measurements are pending for the corrected probe head.

## Benchmarks

- Preliminary listener-role hard blackhole, `timeout=1,500,000 us`: receiver exit in 1,759.3 ms.
- Preliminary listener-role hard blackhole, `timeout=4,500,000 us`: receiver exit in 4,777.4 ms, 4,807.0 ms and 4,772.2 ms across three runs.
- Production-like caller-role hard-blackhole and receiver-frame recovery measurements: pending.

## Known problems / regressions

- The 4.5 s runtime timeout is not yet production-validated because the original calibration used the opposite SRT role from the OBS receiver. The corrected probe now matches caller mode, but new measurements are still required.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- Validate the timeout with the FFmpeg receiver running as SRT caller and use receiver-side frame progress for temporary-outage recovery. The probe implementation has been corrected; measurements are still pending.
- Approval remains intentionally withheld because the long-running PR is draft and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify native OBS controls continue to work while browser-origin requests cannot mutate the phone.
- Verify the OBS-side SRT timeout against the corrected caller-role probe and a real phone stream blackhole, including re-entry into the existing 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions without unnecessary reconnects.
