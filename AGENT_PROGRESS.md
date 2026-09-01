# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) and `/release` to the peer that owns the active reservation; cross-peer takeover and unexpected unbound reservation state fail closed.
- Removed permissive browser CORS/preflight access from the native control port and require `Content-Type: application/json` for mutating routes.
- Added a production-like FFmpeg/libSRT caller fault-injection probe and wired OBS to a 4.5 s SRT I/O timeout plus 2 s connect timeout.
- Added same-phone reconnect stickiness for auto-selected OBS slots, fixed the Windows reconnect-deadline compile break, and made the 45 s hold single-shot so reserve/open retries cannot extend it.
- Made post-expiry failover deterministic: after the hold, auto-selection prefers another eligible phone and uses the failed phone only as sole-candidate fallback.
- Repaired the stale ownership contract so both auto and explicit selection verify that a busy phone is rejected only when another source owns it.

## In progress

- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect behavior, sustained latency, A/V sync, thermals, controls and Virtual Camera startup.
- The automated suite still does not exercise the complete production plugin path from a hard SRT blackhole through `av_read_frame()` exit, the 500 ms reconnect loop, discovery/reservation reacquisition, and restored media.

## Tests performed

- Exact current head `f25a1dc`: Android APK workflow passed.
- Exact current head `f25a1dc`: Windows OBS Plugin workflow passed.
- Exact code head `266a4a6`: Android APK and Windows OBS Plugin workflows passed.
- Post-expiry failover head `e90d037`: Windows OBS Plugin passed; Android repository tests stopped at 79 passed / 1 failed because an older string-based ownership assertion expected the previous expression spelling.
- Contract-fix head `88e37bc`: Android repository-test step passed before the later exact-head full CI rerun.
- Focused reconnect-stickiness suite before post-expiry selection: 22 passed.
- Corrected caller-role FFmpeg/libSRT loopback probe completed successfully for permanent and temporary bidirectional blackholes.

## Benchmarks

- Caller-role hard blackhole with `timeout=4,500,000 us`: 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms to receiver exit (mean 4,798.1 ms).
- Temporary blackholes recovered on the same FFmpeg receiver after restore: 2.0 s → 21.0 ms; 3.0 s → 105.3 ms; 3.5 s → 187.9 ms; 4.0 s → 10.8 ms to next receiver frame.
- These are loopback fault-injection results, not physical Wi-Fi/phone measurements.

## Known problems / regressions

- No current CI regression is known on `agent-dev`; Android APK and Windows OBS Plugin both pass on exact head `f25a1dc`.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.
- The loopback probe validates timeout policy and same-receiver recovery, but not the complete plugin blackhole → `av_read_frame()` exit → reconnect loop → phone reacquisition path.

## Unresolved review feedback

- No unresolved inline review threads currently. The latest recorded review found no new high-confidence correctness/security blocker after the reconnect/failover fixes; physical acceptance testing remains outstanding.

## Inspect before merging

- Verify an auto-selected slot stays on the same phone during the 45 s hold, repeated failures cannot extend that hold, and a healthy alternate is preferred after expiry.
- Verify the failed phone remains usable as fallback when no alternate is eligible and a new hold starts only after actual media recovery.
- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while reserved.
- Verify the 4.5 s timeout with a real phone stream blackhole, including the 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions.
