# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, and same-owner renewals cannot move to a different peer.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Removed permissive browser CORS/preflight support from the native camera-control port so a web page running on the reserving OBS PC cannot inherit the same peer-IP trust boundary and issue camera commands.
- Synced `agent-dev` to the current `main` merge of the legacy Wi-Fi stability work before starting new maintenance.

## In progress

- Validate the completed reservation/control-plane hardening on the latest `agent-dev` head through fresh CI and review feedback.

## Tests performed

- Added structural regression coverage requiring reservation-peer capture, rejection of different-source takeover before `onReserve()`, rejection of same-owner cross-peer renewal, authorization before all camera-control side effects, peer-bound reservation release, peer cleanup when the reservation is released, and no browser CORS/OPTIONS exposure on the native control server.
- Android APK and Windows OBS CI passed on `9b4ec2c` after the peer-bound `/release` change.
- Fresh Android/OBS CI is pending for the browser-origin isolation change.

## Benchmarks

- No performance benchmark in these changes; they are control-plane security/reliability fixes and do not alter the media path.

## Known problems / regressions

- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- CodeRabbit's previously reported cross-peer same-owner reservation takeover has been fixed.
- The completed peer-bound `/release` and browser-origin isolation changes are awaiting review of the latest head.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- During the reconnect hold window, verify a second OBS source/peer cannot replace the existing reservation until it is explicitly released or expires.
- Verify a `/release` replay from a different LAN peer cannot terminate the current reservation, while normal OBS stop/reconfigure still releases it successfully.
- Verify normal OBS camera controls continue to work while browser cross-origin requests to the phone control port are not permitted.
