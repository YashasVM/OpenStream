# OpenStream Repository Rules

## Product objective

OpenStream is one product made from the Android camera app and the native Linux
OBS plugin. Keep changes focused on making that complete Android-to-OBS path
work reliably: discovery, pairing, reservation, camera controls, SRT media,
disconnect, and reconnect must agree across both sides. A change to one side
must preserve or deliberately update its counterpart and the protocol docs.

The Android app must be straightforward to update during development and in
releases. Keep its `applicationId` stable, keep update signing consistent, and
use one documented version source rather than manually changing version
strings in unrelated files. Before changing the application ID or signing
identity, establish why existing installations cannot be upgraded and document
the migration impact. Version code must increase for releases; development APKs
should have a clear install/update command and explain signature or downgrade
errors when those prevent in-place installation.

Improve the UI and UX around the actual camera workflow: show clear connection,
reservation, streaming, and failure states; make connect/disconnect and phone
selection discoverable; and keep Android and OBS labels/actions consistent.
Prefer fixes tied to observed user friction over broad visual changes that do
not improve the end-to-end workflow.

For each change, check which part of the Android-to-Linux OBS workflow it
affects, preserve compatibility where required, and leave the system in a
state that can be built, installed, and manually exercised. Do not claim the
whole product works based only on unit or contract tests; report which parts
were actually verified and which require a physical Android device or OBS.

These rules apply to every OpenStream change.

1. Never replace source timestamps with arrival time.
2. Never re-encode ISO video.
3. Never use unbounded queues.
4. Never put OBS dependencies inside the engine.
5. Never let the UI own media sessions.
6. Never perform network or disk work on the UI thread.
7. Never perform ordinary CPU frame copies in the hardware path.
8. Never allow one camera failure to interrupt another.
9. Never enable a software codec silently.
10. Never create a second phone video encoder in standard mode.
11. Never mix all microphones automatically.
12. Never promise genlock or guaranteed frame-perfect sync.
13. Keep unrelated changes in separate PRs.
14. Every performance change must include before-and-after measurements.
15. Every media change must include behavioural tests.
16. Every queue must declare its capacity and overflow policy.
17. Every recording gap must be surfaced and logged.
18. Hardware acceleration must have an explicit fallback and warning.
19. Preserve legacy compatibility through adapters, not core contamination.

## Completion workflow

After completing a task, create a pull request on GitHub for the finished changes.
