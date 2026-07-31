# OpenStream Repository Rules

These rules apply to every OpenStream V4 change.

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
13. Never combine multiple major phases into one PR.
14. Every performance change must include before-and-after measurements.
15. Every media change must include behavioural tests.
16. Every queue must declare its capacity and overflow policy.
17. Every recording gap must be surfaced and logged.
18. Hardware acceleration must have an explicit fallback and warning.
19. Preserve legacy compatibility through adapters, not core contamination.
20. Virtual microphone work is outside the initial V4 release.

## Completion workflow

After completing a task, create a pull request on GitHub for the finished changes.

