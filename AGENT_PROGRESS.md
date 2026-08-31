# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) and `/release` to the peer that owns the active reservation; cross-peer takeover and unexpected unbound reservation state fail closed.
- Removed permissive browser CORS/preflight access from the native control port and require `Content-Type: application/json` for mutating routes.
- Added a production-like FFmpeg/libSRT caller fault-injection probe and wired OBS to a 4.5 s SRT I/O timeout plus 2 s connect timeout.
- Measured five caller-role hard blackholes at 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms; temporary 2.0-4.0 s blackholes recovered on the same receiver after traffic restore.
- Added same-phone reconnect stickiness for auto-selected OBS slots and fixed the Windows `min` macro compile break in the reconnect deadline.
- Made reconnect holds single-shot per failure episode so reserve/open retries cannot extend the 45 s deadline; a new hold starts only after actual media recovery.
- Made post-expiry failover deterministic for the previous phone: after the 45 s hold, auto-selection prefers another eligible phone when one exists and uses the expired phone only as a fallback when it is the sole eligible candidate. Added focused contracts for the bounded hold and post-expiry selection semantics.

## In progress

- Validate the new post-expiry failover head in Android and Windows/OBS CI.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect behavior, sustained latency, A/V sync, thermals, controls and Virtual Camera startup.

## Tests performed

- Exact-head `f932bbb` Android APK workflow passed.
- Exact-head `f932bbb` Windows OBS Plugin workflow passed.
- Focused reconnect-stickiness repository contract suite before the post-expiry selection change: 22 passed.
- Added post-expiry regression contracts covering alternate preference, sole-candidate fallback, and reset after media recovery; exact-head workflows have not appeared yet, so the new head is not claimed CI-green.
- Corrected caller-role FFmpeg/libSRT loopback probe completed successfully for permanent and temporary bidirectional blackholes.

## Benchmarks

- Caller-role hard blackhole, `timeout=4,500,000 us`: receiver exit in 4,830.3 ms, 4,837.8 ms, 4,823.4 ms, 4,744.4 ms and 4,754.6 ms across five runs (mean 4,798.1 ms).
- Caller-role temporary blackholes recovered on the same FFmpeg receiver after restore: 2.0 s outage → 21.0 ms to next receiver frame; 3.0 s → 105.3 ms; 3.5 s → 187.9 ms; 4.0 s → 10.8 ms.
- These are loopback fault-injection results, not physical Wi-Fi/phone measurements.

## Known problems / regressions

- The post-expiry failover code is awaiting exact-head CI; do not treat it as validated until Android and Windows/OBS workflows pass.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.
- The loopback probe validates timeout policy and same-receiver recovery, but not the complete plugin blackhole → `av_read_frame()` exit → reconnect loop → phone reacquisition path.

## Unresolved review feedback

- No unresolved inline review threads currently. CodeRabbit's latest recorded review has no actionable comments; draft PRs are not automatically reviewed by default.

## Inspect before merging

- Verify repeated reserve/open failures cannot extend the original 45 s reconnect hold.
- Verify an auto-selected slot stays on the same phone during the hold, then prefers a healthy alternate after expiry when one exists while retaining the original phone as sole-candidate fallback.
- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while reserved.
- Verify reconnect-held reservations cannot be stolen or released by another LAN peer.
- Verify the 4.5 s timeout with a real phone stream blackhole, including the existing 500 ms reconnect loop and recovery from shorter Wi-Fi interruptions.
