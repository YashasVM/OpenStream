# Agent progress

## Completed since last review

- Bound Android camera mutation controls (`/zoom`, `/torch`, `/lens`, `/identify`) to the network peer that successfully owns the current camera reservation, preventing unrelated LAN clients from changing an active phone camera.
- Hardened reservation ownership so an active/reconnect-held reservation cannot be replaced by a different source ID, and same-owner renewals cannot move to a different peer.
- Bound `/release` to the same controller peer before reservation-token validation, so a replayed/leaked token from another LAN host cannot terminate the active reservation.
- Synced `agent-dev` to the current `main` merge of the legacy Wi-Fi stability work before starting new maintenance.

## In progress

- Validate the completed reservation/control-plane hardening on the latest `agent-dev` head through fresh CI and review feedback.

## Tests performed

- Added structural regression coverage requiring reservation-peer capture, rejection of different-source takeover before `onReserve()`, rejection of same-owner cross-peer renewal, authorization before all camera-control side effects, peer-bound reservation release, and peer cleanup when the reservation is released.
- Android APK and Windows OBS CI passed on `f9b55ba` after the different-source takeover fix.
- Fresh Android/OBS CI is pending for the peer-bound `/release` change.

## Benchmarks

- No performance benchmark in these changes; they are control-plane security/reliability fixes and do not alter the media path.

## Known problems / regressions

- Physical phone → Wi-Fi → OBS testing is still needed for sustained latency, A/V sync, thermals, reconnect behavior, Virtual Camera startup, and vendor-specific Android camera/codec behavior.

## Unresolved review feedback

- CodeRabbit's different-source reservation-takeover finding is implemented and passed CI on `f9b55ba`; a fresh review was requested.
- The new peer-bound `/release` hardening is awaiting CI and review.

## Inspect before merging

- Verify camera controls work from the reserving OBS PC and are rejected from a second LAN device while the phone is reserved.
- During the reconnect hold window, verify a second OBS source/peer cannot replace the existing reservation until it is explicitly released or expires.
- Verify a `/release` replay from a different LAN peer cannot terminate the current reservation, while normal OBS stop/reconfigure still releases it successfully.
