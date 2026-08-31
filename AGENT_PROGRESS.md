# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and any unexpected reservation that lacks a bound controller now fails closed instead of allowing a LAN client to claim or release it.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for every mutating route before body parsing/dispatch. This blocks CORS-safelisted `text/plain`/form POSTs from web pages running on the authorized OBS host while preserving the native OBS client.
- Fixed the reservation contract regression introduced with the fail-closed guard: the test now expects the current `currentControllerAddress == null` behavior instead of the superseded `!= null` condition.
- Synced `agent-dev` to the current `main` merge of the legacy Wi-Fi stability work before starting new maintenance.
- Added `tools/srt_timeout_probe.py`, a bidirectional UDP-relay fault-injection probe that establishes a real FFmpeg/libSRT MPEG-TS flow, blackholes both directions, and measures receiver exit latency for candidate SRT I/O timeouts.

## In progress

- Receiver-side SRT reconnect hardening: FFmpeg's SRT protocol exposes `timeout` in microseconds as a cap on read/write/connect operations and a separate millisecond `connect_timeout`. The OBS auto-caller currently sets only mode + latency, while Android uses a 2 s connect timeout and 4 s peer-idle timeout. Fault injection now shows a 4.5 s FFmpeg SRT timeout exits consistently at about 4.77–4.81 s after a hard blackhole on this environment. The runtime OBS receiver still needs the bounded timeout wired into its SRT input options and then validated in CI and on a physical phone/Wi-Fi path.
- Physical phone → Wi-Fi → OBS acceptance testing remains outstanding for reconnect/control behavior, sustained latency, A/V sync, thermals and Virtual Camera startup.

## Tests performed

- Added structural regression coverage requiring reservation-peer capture, rejection of different-source takeover before `onReserve()`, rejection of same-owner cross-peer renewal, fail-closed handling for unbound active reservations before reserve/release side effects, authorization before all camera-control side effects, peer-bound reservation release, peer cleanup when the reservation is released, no browser CORS/OPTIONS exposure, and `application/json` enforcement before mutating-route body parsing or dispatch.
- Android APK and Windows OBS CI are both green on exact head `4411344`.
- The earlier Android failure on `d7e1251` was isolated to a stale repository-test expectation; runtime behavior did not need to change.
- Reviewed the native Android SRT socket configuration and the OBS FFmpeg receiver path. Android explicitly configures live transmission, too-late packet drop, 120 ms send timeout, 2 s connect timeout and 4 s peer-idle timeout; the OBS auto-caller currently has no explicit read timeout.
- Verified against current FFmpeg protocol documentation that SRT `timeout` caps read/write/connect operations and that `connect_timeout` is independently configurable for caller/rendezvous setup.
- `python -m py_compile tools/srt_timeout_probe.py` passed locally; the probe exercised the locally installed FFmpeg build with SRT support successfully.

## Benchmarks

- Synthetic FFmpeg/libSRT blackhole probe with `timeout=1,500,000 us`: receiver exited in 1,759.3 ms after bidirectional traffic was dropped.
- Synthetic FFmpeg/libSRT blackhole probe with candidate `timeout=4,500,000 us`: 4,777.4 ms, 4,807.0 ms, and 4,772.2 ms across three runs. This validates timeout semantics and gives a practical upper bound target before the existing 500 ms OBS reconnect delay, but it is not yet a physical phone/Wi-Fi benchmark.

## Known problems / regressions

- The OBS runtime receiver still lacks an explicit bounded SRT I/O timeout, so a silent network blackhole can potentially leave `av_read_frame()` waiting on the underlying library longer than the intended reconnect budget.
- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- No unresolved CodeRabbit correctness/security blocker is currently identified for the completed camera-control hardening.
- Approval is still intentionally withheld because the long-running PR remains draft and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- During the reconnect hold window, verify a second OBS source/peer cannot replace the existing reservation until it is explicitly released or expires.
- Verify a `/release` replay from a different LAN peer cannot terminate the current reservation, while normal OBS stop/reconfigure still releases it successfully.
- Verify native OBS camera controls continue to work with JSON requests while browser-origin `text/plain`/form POSTs and cross-origin JSON requests cannot mutate the phone.
- For the SRT reconnect task, verify the final OBS-side timeout exits a real phone stream blackhole near the synthetic ~4.8 s result and then re-enters the existing 500 ms reconnect loop without false reconnects during ordinary Wi-Fi jitter.
