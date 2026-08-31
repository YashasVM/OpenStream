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
- Fixed the Windows reconnect-deadline compile failure caused by the Win32 `min` macro expanding inside `std::chrono::steady_clock::time_point::min()`; the deadline now uses a macro-safe default `time_point{}` initializer, with a contract regression guard.

## In progress

- Fix bounded reconnect failover: the current reconnect path refreshes the 45 s deadline after every successful re-reservation / failed SRT-open attempt. If the old phone remains discoverable but its media endpoint stays broken, an auto slot can therefore keep extending the hold indefinitely instead of allowing another phone after 45 s.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for real reconnect behavior, sustained latency, A/V sync, thermals, controls and Virtual Camera startup.
- Real two-phone validation is still needed to prove an auto-selected slot cannot migrate during the 45 s reconnect hold and can reassign after expiry.

## Tests performed

- Exact-head `1e616ad` Android APK workflow passed.
- Exact-head `1e616ad` Windows OBS Plugin workflow passed.
- Focused repository contract suite after reconnect-stickiness implementation: 22 passed in 0.10 s.
- Repository contract tests cover placement of the 4.5 s I/O and 2 s connect timeout before `avformat_open_input` and assert the 45 s same-phone reconnect selection path exists.
- Corrected caller-role FFmpeg/libSRT loopback probe completed successfully for permanent and temporary bidirectional blackholes.

## Benchmarks

- Caller-role hard blackhole, `timeout=4,500,000 us`: receiver exit in 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms across five runs (mean 4,798.1 ms).
- Caller-role temporary blackholes recovered on the same FFmpeg receiver after restore: 2.0 s outage → 21.0 ms to next receiver frame; 3.0 s → 105.3 ms; 3.5 s → 187.9 ms; 4.0 s → 10.8 ms.
- These are loopback fault-injection results, not physical Wi-Fi/phone measurements.

## Known problems / regressions

- The 45 s same-phone failover window is not currently strictly bounded: repeated successful reserve calls / failed media opens can refresh the deadline, so a discoverable-but-broken phone may pin an auto slot indefinitely. Treat this as a merge blocker until reconnect attempts stop extending an active hold.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.
- The loopback probe validates the timeout policy and same-receiver recovery behavior, but does not measure the plugin's full blackhole → `av_read_frame()` exit → existing 500 ms reconnect-loop → phone reacquisition path.
- The same-phone reconnect behavior is contract-tested and CI-green but still needs a real two-phone acceptance test to prove no scene-source migration occurs during a discovery outage.

## Unresolved review feedback

- No unresolved inline review findings currently. CodeRabbit reports no actionable comments on the recent review. Macroscope's billing skip is not treated as code feedback.
- Self-review found the reconnect-deadline refresh issue above; it is treated as a correctness blocker even though CI is green.

## Inspect before merging

- Verify the 45 s reconnect hold starts from a real disconnect/failure boundary and is not extended by repeated reserve/open retries; after expiry, a healthy alternate phone must become selectable.
- Verify an auto-selected slot remains pinned to the same reserved phone during the 45 s reconnect window even when that phone disappears from discovery temporarily and other phones remain available; verify reassignment is allowed after expiry.
- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify native OBS controls continue to work while browser-origin requests cannot mutate the phone.
- Verify the 4.5 s timeout with a real phone stream blackhole, including re-entry into the existing 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions without unnecessary reconnects.
