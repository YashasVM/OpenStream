# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, same-owner renewals cannot move to a different peer, and any unexpected reservation that lacks a bound controller now fails closed instead of allowing a LAN client to claim or release it.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and require `Content-Type: application/json` for every mutating route before body parsing/dispatch. This blocks CORS-safelisted `text/plain`/form POSTs from web pages running on the authorized OBS host while preserving the native OBS client.
- Fixed the reservation contract regression introduced with the fail-closed guard: the test now expects the current `currentControllerAddress == null` behavior instead of the superseded `!= null` condition.
- Synced `agent-dev` to the current `main` merge of the legacy Wi-Fi stability work before starting new maintenance.

## In progress

- Physical phone → Wi-Fi → OBS acceptance testing remains the main outstanding validation; no additional code change is justified until that path exposes a real issue or a higher-value repository problem appears.

## Tests performed

- Added structural regression coverage requiring reservation-peer capture, rejection of different-source takeover before `onReserve()`, rejection of same-owner cross-peer renewal, fail-closed handling for unbound active reservations before reserve/release side effects, authorization before all camera-control side effects, peer-bound reservation release, peer cleanup when the reservation is released, no browser CORS/OPTIONS exposure, and `application/json` enforcement before mutating-route body parsing or dispatch.
- Android APK and Windows OBS CI are both green on exact head `f004c3b` after correcting the stale structural assertion in the reservation contract test.
- The earlier Android failure on `d7e1251` was isolated to that stale repository-test expectation; runtime behavior did not need to change.

## Benchmarks

- No performance benchmark in these changes; they are control-plane security/reliability fixes and do not alter the media path.

## Known problems / regressions

- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- No unresolved code/CI blocker is currently identified. Latest review found the corrected reservation contract aligned with the fail-closed implementation, and both exact-head workflows are green.
- Approval is still intentionally withheld because the long-running PR remains draft and physical phone/OBS acceptance testing is outstanding.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- During the reconnect hold window, verify a second OBS source/peer cannot replace the existing reservation until it is explicitly released or expires.
- Verify a `/release` replay from a different LAN peer cannot terminate the current reservation, while normal OBS stop/reconfigure still releases it successfully.
- Verify native OBS camera controls continue to work with JSON requests while browser-origin `text/plain`/form POSTs and cross-origin JSON requests cannot mutate the phone.
