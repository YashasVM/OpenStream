# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, and same-owner renewals cannot move to a different peer.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port and now require `Content-Type: application/json` for every mutating route before body parsing/dispatch. This blocks CORS-safelisted `text/plain`/form POSTs from web pages running on the authorized OBS host while preserving the native OBS client.
- Synced `agent-dev` to the current `main` merge of the legacy Wi-Fi stability work before starting new maintenance.

## In progress

- Validate the completed reservation/control-plane hardening on the latest `agent-dev` head through fresh CI and review feedback.

## Tests performed

- Added structural regression coverage requiring reservation-peer capture, rejection of different-source takeover before `onReserve()`, rejection of same-owner cross-peer renewal, authorization before all camera-control side effects, peer-bound reservation release, peer cleanup when the reservation is released, no browser CORS/OPTIONS exposure, and `application/json` enforcement before mutating-route body parsing or dispatch.
- Android APK and Windows OBS CI passed on `9b4ec2c` after the peer-bound `/release` change.
- Fresh Android/OBS CI is pending for the JSON-only browser-origin isolation fix.

## Benchmarks

- No performance benchmark in these changes; they are control-plane security/reliability fixes and do not alter the media path.

## Known problems / regressions

- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- CodeRabbit's cross-peer same-owner and different-source reservation takeover findings have been fixed.
- CodeRabbit's browser-origin `text/plain` POST bypass has been fixed by requiring `application/json` before all mutating route dispatch; the latest head is awaiting CI and follow-up review.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- During the reconnect hold window, verify a second OBS source/peer cannot replace the existing reservation until it is explicitly released or expires.
- Verify a `/release` replay from a different LAN peer cannot terminate the current reservation, while normal OBS stop/reconfigure still releases it successfully.
- Verify native OBS camera controls continue to work with JSON requests while browser-origin `text/plain`/form POSTs and cross-origin JSON requests cannot mutate the phone.
