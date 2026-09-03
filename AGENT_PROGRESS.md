# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) and `/release` to the peer that owns the active reservation; cross-peer takeover and unexpected unbound reservation state fail closed.
- Removed permissive browser CORS/preflight access from the native control port and require `Content-Type: application/json` for mutating routes.
- Added a production-like FFmpeg/libSRT caller fault-injection probe and wired OBS to a 4.5 s SRT I/O timeout plus 2 s connect timeout.
- Hardened the SRT timeout probe so FFmpeg diagnostic output cannot fill an unread stderr pipe and distort timeout/recovery measurements.
- Added same-phone reconnect stickiness for auto-selected OBS slots and made the 45 s hold single-shot so failed reserve/open retries cannot extend it.
- Made post-expiry failover deterministic: another eligible phone is preferred after hold expiry, while the failed phone remains sole-candidate fallback.
- Added reconnect-recovery hysteresis: a reopened stream must output 30 decoded video frames before the failure episode is reset, so one-frame/flapping reconnects cannot renew the same-phone hold.
- Bounded OBS camera-control TCP connection establishment to 1 s with nonblocking connect completion checks, preventing an unreachable/blackholed phone from leaving reservation, release, or control work stuck in the OS TCP connect timeout.

## In progress

- Physical two-phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect behavior, short/flapping decoded bursts, sustained latency, A/V sync, thermals, controls and Virtual Camera startup.
- The automated suite still does not exercise the complete production plugin path from a hard SRT blackhole through `av_read_frame()` exit, the 500 ms reconnect loop, discovery/reservation reacquisition, and restored media.
- A physical unreachable-phone teardown/release check remains outstanding for the new camera-control connect deadline.

## Tests performed

- Exact connect-deadline head `3032339`: Android APK and Windows OBS Plugin workflows both passed.
- Exact reconnect-hysteresis head `4cb7112`: Android APK and Windows OBS Plugin workflows both passed.
- Exact runtime/probe head `d914235`: Android APK and Windows OBS Plugin workflows both passed.
- Exact code head `266a4a6`: Android APK and Windows OBS Plugin workflows both passed.
- Focused reconnect-stickiness suite before post-expiry selection: 22 passed.
- Corrected caller-role FFmpeg/libSRT loopback probe completed successfully for permanent and temporary bidirectional blackholes.

## Benchmarks

- Caller-role hard blackhole with `timeout=4,500,000 us`: 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms to receiver exit (mean 4,798.1 ms).
- Temporary blackholes recovered on the same FFmpeg receiver after restore: 2.0 s → 21.0 ms; 3.0 s → 105.3 ms; 3.5 s → 187.9 ms; 4.0 s → 10.8 ms to next receiver frame.
- These are loopback fault-injection results, not physical Wi-Fi/phone measurements, and predate the stderr backpressure hardening.

## Known problems / regressions

- No production-code regression is currently known on exact validated head `3032339`; physical unreachable-phone acceptance for the new connect deadline is still outstanding.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, vendor-specific Android camera/codec behavior, and unreachable-phone control teardown.
- The loopback probe validates timeout policy and same-receiver recovery, but not the complete plugin blackhole → `av_read_frame()` exit → reconnect loop → phone reacquisition path.

## Unresolved review feedback

- No unresolved inline review threads or high-confidence code-review blockers are currently known. The latest CodeRabbit pass produced no actionable comments.
- Macroscope skipped the latest correctness review because of its workspace billing limit; this is a review-coverage gap, not a code failure.

## Inspect before merging

- Verify an auto-selected slot stays on the same phone during the 45 s hold, repeated failures and short decoded bursts cannot renew that hold, and a healthy alternate is preferred after expiry.
- Verify the failed phone remains usable as fallback when no alternate is eligible and a new hold starts only after sustained media recovery.
- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while reserved.
- Verify stopping/removing an OBS source while the phone control IP is unreachable does not hang on TCP connect and reservation release retries remain bounded.
- Verify the 4.5 s timeout with a real phone stream blackhole, including the 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions.
