# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and any unexpected reservation that lacks a bound controller now fails closed instead of allowing a LAN client to claim or release it.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for every mutating route before body parsing/dispatch.
- Added `tools/srt_timeout_probe.py`, a real FFmpeg/libSRT fault-injection probe for receiver timeout calibration, and extended it to restore traffic after temporary blackholes so the chosen timeout can be checked for both bounded failure detection and unnecessary reconnect risk.

## In progress

- Receiver-side SRT reconnect hardening. Synthetic blackhole testing supports a 4.5 s FFmpeg SRT I/O timeout: hard blackholes exit at about 4.77–4.81 s, while temporary 2.0 s, 3.0 s, 3.5 s and 4.0 s blackholes all recovered on the same receiver after traffic was restored. The runtime OBS receiver still needs this bounded timeout wired into its SRT caller path, followed by exact-head CI and physical phone/Wi-Fi validation.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect/control behavior, sustained latency, A/V sync, thermals and Virtual Camera startup.

## Tests performed

- Android APK and Windows OBS CI are both green on exact head `743b2b2`.
- Latest CodeRabbit review on `743b2b2` found no high-confidence correctness/security issue in the SRT probe; no review threads are unresolved.
- `python -m py_compile` passed for the temporary-blackhole probe logic in the current environment, using the locally installed FFmpeg build with SRT support.
- Temporary-blackhole recovery measurements with `timeout=4,500,000 us`: 2.0 s outage recovered in 566 ms after restore; 3.0 s in 709 ms; 3.5 s in 612 ms; 4.0 s in 610 ms. These are synthetic loopback/libSRT measurements, not physical Wi-Fi results.

## Benchmarks

- Hard blackhole, `timeout=1,500,000 us`: receiver exit in 1,759.3 ms.
- Hard blackhole, candidate `timeout=4,500,000 us`: receiver exit in 4,777.4 ms, 4,807.0 ms and 4,772.2 ms across three runs.
- Temporary blackholes with the same 4.5 s timeout recovered without receiver exit at 2.0 s, 3.0 s, 3.5 s and 4.0 s outages; post-restore media-flow recovery was 566–709 ms.

## Known problems / regressions

- The OBS runtime receiver still lacks an explicit bounded SRT I/O timeout, so a silent network blackhole can potentially leave `av_read_frame()` waiting longer than the intended reconnect budget.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- No unresolved CodeRabbit correctness/security blocker is currently identified.
- Approval is still intentionally withheld because the long-running PR remains draft, the runtime SRT timeout is not yet wired, and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify native OBS controls continue to work while browser-origin requests cannot mutate the phone.
- For SRT reconnect hardening, verify the final OBS-side timeout exits a real phone stream blackhole near the synthetic ~4.8 s result, re-enters the existing 500 ms reconnect loop, and does not force reconnects during shorter Wi-Fi interruptions.
