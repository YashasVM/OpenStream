# Contributing to OpenStream

## Check the current terms first

This repository has no top-level license. The project owner has not yet published project-wide reuse or contribution terms. You can inspect and build the code, but do not assume that the repository grants permission to reuse it or that a patch is accepted under a particular license. Ask the owner to publish the approved license before submitting code for inclusion.

## Build and check

OpenStream has Android and native OBS components. Read the setup and build instructions in [`README.md`](README.md), [`docs/linux.md`](docs/linux.md), and [`docs/testing.md`](docs/testing.md). Use the check for the component you change.

For Android JVM tests, run:

```sh
cd android
./gradlew testDebugUnitTest
```

For repository contract tests, run:

```sh
python -m pytest
```

Native OBS builds require the matching OBS, FFmpeg, and Qt development files. Follow [`docs/linux.md`](docs/linux.md) on Linux or the Windows build instructions in `README.md`.

## Prepare a change

- Keep a change focused on one Android-to-OBS workflow issue.
- Update Android, OBS, and [`docs/protocol.md`](docs/protocol.md) together when a wire behavior changes.
- Add behavioral checks for media changes. Record physical phone and OBS checks separately from automated checks.
- Include the commands you ran and identify checks that need a physical Android device or OBS.
- Do not include logs, screenshots, addresses, tokens, or media that identify a person unless they are necessary and the person has agreed to share them.

## Open a report

Use the repository's bug report or feature request form. Search existing issues first. For a possible security vulnerability, follow [`SECURITY.md`](SECURITY.md); do not use a public issue.
