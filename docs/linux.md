# OpenStream OBS plugin on Linux

Native Linux support for the OpenStream OBS source plugin: build, install,
and validation notes for this branch.

## Scope

- Native (distro or PPA) OBS Studio on Linux x86_64 with Qt6.
- One OBS source reserves one phone. Reuse that source across scenes with Add
  Existing; a phone cannot be reserved by two sources at once.
- **No Flatpak/Snap guarantee.** Sandboxed OBS builds isolate the plugin
  directory and network namespace, so the install paths and discovery
  behaviour below are not validated for them.

## Dependencies

Install the development packages first (example for Debian/Ubuntu; the
build script never installs system packages itself):

- `cmake` (>= 3.24), `g++` (C++20), `pkg-config`, `ninja` (optional)
- `libobs-dev` (>= 30; provides the OBS core and frontend API development
  files)
- `libavformat-dev`, `libavcodec-dev`, `libavutil-dev`, `libswscale-dev`
- `qt6-base-dev` (>= 6.2, for Qt6 Core and Widgets)

Notes:

- The native Linux module registers the **OpenStream Camera Control** dock in
  OBS. It lists discovered phones, lets you name the source, connect or release
  a reservation, and send zoom, lens, torch, and identify commands. These UI
  dependencies stay in the OBS plugin target; the media engine does not link
  OBS frontend or Qt Widgets.
- `libobs-dev` supplies the frontend API headers, link library, and
  `obs-frontend-api.pc` on Ubuntu 24.04; there is no separate frontend
  development package to install. The build script checks this API and Qt
  Widgets before configuring.
- Linux links the **system OBS/FFmpeg ABI** (whatever the distro OBS was
  built against). There is intentionally no FFmpeg version pin on this path,
  unlike the Windows release build, which stays pinned to OBS 32.2.1 /
  avformat-62 (see `obs-plugin/CMakeLists.txt`).
- Reference configurations: local validation used libobs 32.2.2, FFmpeg
  (avformat 63), Qt 6.11.2; CI (`obs-plugin-linux.yml`) uses Ubuntu 24.04
  with libobs-dev 30.0.2, FFmpeg 6.1.1, qt6-base-dev 6.4.2, and fails
  visibly below libobs 30 / Qt 6.2.

## Build

```sh
./build_plugin_linux.sh
```

This configures incrementally (`obs-plugin/build` by default, override with
`OPENSTREAM_PLUGIN_BUILD_DIR`), builds `shin-obs.so`, and runs the
C++ contract tests via `ctest`. Useful variants:

- `OPENSTREAM_PLUGIN_BUILD_DIR=/tmp/openstream-linux-build ./build_plugin_linux.sh`
  — validation build outside the repo.
- `OPENSTREAM_PLUGIN_PACKAGE_DIR="$PWD/artifacts" ./build_plugin_linux.sh`
  — also stages `shin-obs-linux-x86_64.tar.gz` (module + installer).
- `./build_plugin_linux.sh --package-only` — repackage an existing build.
- `./build_plugin_linux.sh --install-user` / `--install-system` — build,
  test, then install (see below).

## Install / uninstall

Per-user install (default, no root). Respects `XDG_CONFIG_HOME`:

```sh
./tools/installer/install-shin-plugin-linux.sh
# installs to ${XDG_CONFIG_HOME:-$HOME/.config}/obs-studio/plugins/shin-obs/bin/64bit/shin-obs.so
```

System-wide install (requires root; the OBS system plugin dir is
auto-detected, e.g. `/usr/lib/obs-plugins`):

```sh
sudo ./tools/installer/install-shin-plugin-linux.sh --system
```

Testing hook (touches nothing outside the given directory):

```sh
./tools/installer/install-shin-plugin-linux.sh --dest-dir /tmp/os-install-test
```

The install is atomic (stage to a temp file in the destination, then
rename), replaces the canonical module, and removes only other-named stale
copies (`shin-beta-obs.so`, `libshin-obs.so`, the legacy flat
per-user copy) after the new copy succeeds.

Uninstall: delete `shin-obs.so` from the user or system plugin
directory above and restart OBS. There is no uninstaller script.

## Behavioural notes

- Software decode warning: the receiver probes for a hardware decode
  device but has no validated zero-copy hardware frame path, so decoding
  always falls back to software with an explicit OBS log warning. Expect
  corresponding CPU load on the OBS machine; this is stated, not measured,
  here.
- Reliability: the camera-control TCP channel suppresses SIGPIPE per send
  (and per socket where supported), so a disconnected phone fails the
  command with an error instead of terminating OBS. Covered by
  `obs-plugin/tests/test_sigpipe.cpp`, which reproduces the disconnected-
  peer crash with flags 0 and proves the production flags survive with
  EPIPE.
- Source timestamps are preserved end to end (MediaClock maps the phone
  clock into OBS time; gaps are surfaced and logged, never replaced with
  arrival time). Every receiver queue declares its capacity and overflow
  policy.

## Exact validation limits

What was actually checked for this branch (local machine):

- Direct `g++` C++20 syntax checks passed for the source, control client, and
  dock against the installed OBS 32.2.2, FFmpeg, and Qt6 development headers.
- A manual shared-library link produced `shin-obs.so`. `ldd` confirmed links
  to `libobs`, `libobs-frontend-api`, FFmpeg, Qt6 Core, and Qt6 Widgets, with no
  missing libraries. The dock registration symbols are present in the module.
- The Linux installer smoke test copied the linked module into an isolated
  `--dest-dir`; the installed file matched the built module byte for byte.
- Focused Python contracts passed (4 tests), and `bash -n` passed for the
  Linux build and installer scripts.
- CMake and CTest were not run because CMake is unavailable in this environment.
- See [reliability audit](reliability-audit.md) for the final check results.

What was NOT validated and remains a blocker for release claims:

- Loading `shin-obs.so` inside a running native OBS GUI on Linux, including
  dock behavior.
- A physical phone streaming over Wi-Fi (discovery, reservation, SRT
  media, reconnect, controls).
- Any Flatpak/Snap OBS build, any distro other than the validation
  machine, or physical camera sessions.
- No performance measurements were taken; no latency/CPU claims are made.
