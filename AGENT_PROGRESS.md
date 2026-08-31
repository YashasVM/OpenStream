# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and unexpected reservation state without a bound controller fails closed.
- Bound `/release` to the controller peer before reservation-token validation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for mutating routes.
- Added `tools/srt_timeout_probe.py`, corrected it to measure the receiver in production-like SRT `mode=caller`, and use receiver-side FFmpeg frame progress for recovery validation.
- Wired the OBS FFmpeg input to a 4.5 s SRT I/O timeout plus 2 s connect timeout before `avformat_open_input`, with a contract test locking values and placement.
- Re-ran the corrected caller-role probe. Hard blackholes exited in 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms; temporary 2.0-4.0 s blackholes all recovered on the same receiver after restore.
- Fixed auto-slot reconnect stickiness: after an auto-selected phone disconnects, OBS now pins retries to that phone's instance ID for the 45 s reconnect reservation window instead of silently selecting another available camera. Generic auto-selection resumes only after the hold expires. Explicitly selected phones are unchanged.

## In progress

- Validate the same-phone reconnect hold with the Windows OBS build and, when hardware is available, a real multi-phone Wi-Fi scenario where the active phone disappears from discovery while another phone remains available.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for real reconnect behavior, sustained latency, A/V sync, thermals, controls and Virtual Camera startup.

## Tests performed

- Focused repository contract suite after reconnect-stickiness implementation: 22 passed in 0.10 s.
- Android APK and Windows OBS CI were green on the pre-stickiness head `475c26f`; fresh exact-head CI is required after the reconnect change.
- Repository contract tests cover placement of the 4.5 s I/O and 2 s connect timeout before `avformat_open_input` and now assert the 45 s same-phone reconnect selection path exists.
- Corrected caller-role FFmpeg/libSRT loopback probe completed successfully for permanent and temporary bidirectional blackholes.
- Self-review confirmed the temporary patch workflow used to apply the connector-limited edit was removed from the final tree.

## Benchmarks

- Caller-role hard blackhole, `timeout=4,500,000 us`: receiver exit in 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms across five runs (mean 4,798.1 ms).
- Caller-role temporary blackholes recovered on the same FFmpeg receiver after restore: 2.0 s outage → 21.0 ms to next receiver frame; 3.0 s → 105.3 ms; 3.5 s → 187.9 ms; 4.0 s → 10.8 ms.
- These are loopback fault-injection results, not physical Wi-Fi/phone measurements.

## Known problems / regressions

- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.
- The loopback probe validates the timeout policy and same-receiver recovery behavior, but does not measure the plugin's full blackhole → `av_read_frame()` exit → existing 500 ms reconnect-loop → phone reacquisition path.
- The same-phone reconnect behavior is contract-tested but still needs a real two-phone acceptance test to prove no scene-source migration occurs during a discovery outage.

## Unresolved review feedback

- No unresolved inline review findings currently. Approval remains intentionally withheld because the long-running PR is draft and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify an auto-selected slot remains pinned to the same reserved phone during the 45 s reconnect window even when that phone disappears from discovery temporarily and other phones remain available; verify reassignment is allowed after expiry.
- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify native OBS controls continue to work while browser-origin requests cannot mutate the phone.
- Verify the 4.5 s timeout with a real phone stream blackhole, including re-entry into the existing 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions without unnecessary reconnects.
