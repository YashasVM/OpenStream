# OpenStream OBS plugin on Linux

Native Linux support for the OpenStream OBS source plugin: build, install,
and validation notes for this branch.

## Scope

- Native (distro or PPA) OBS Studio on Linux x86_64 with Qt6.
- **One active camera per OBS process**. Reuse the same source across scenes
  with Add Existing. Physical phone acceptance testing is still required.
- **No Flatpak/Snap guarantee.** Sandboxed OBS builds isolate the plugin
  directory and network namespace, so the install paths and discovery
  behaviour below are not validated for them.

## Dependencies

Install the development packages first (example for Debian/Ubuntu; the
build script never installs system packages itself):

- `cmake` (>= 3.24), `g++` (C++20), `pkg-config`, `ninja` (optional)
- `libobs-dev` (>= 30; provides `libobs`, `obs-frontend-api` headers/library)
- `libavformat-dev`, `libavcodec-dev`, `libavutil-dev`, `libswscale-dev`
- `qt6-base-dev` (>= 6.2, for Qt6 Network + Widgets)

Notes:

- `obs-frontend-api.pc` may be absent on distro packages. That is legitimate:
  `obs-plugin/CMakeLists.txt` probes it with pkg-config and falls back to
  standard header/library lookup, which still requires the frontend headers
  and `libobs-frontend-api` from `libobs-dev`.
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
`OPENSTREAM_PLUGIN_BUILD_DIR`), builds `openstream-obs.so`, and runs the
C++ contract tests via `ctest`. Useful variants:

- `OPENSTREAM_PLUGIN_BUILD_DIR=/tmp/openstream-linux-build ./build_plugin_linux.sh`
  — validation build outside the repo.
- `OPENSTREAM_PLUGIN_PACKAGE_DIR="$PWD/artifacts" ./build_plugin_linux.sh`
  — also stages `openstream-obs-linux-x86_64.tar.gz` (module + installer).
- `./build_plugin_linux.sh --package-only` — repackage an existing build.
- `./build_plugin_linux.sh --install-user` / `--install-system` — build,
  test, then install (see below).

## Install / uninstall

Per-user install (default, no root). Respects `XDG_CONFIG_HOME`:

```sh
./tools/installer/install-openstream-plugin-linux.sh
# installs to ${XDG_CONFIG_HOME:-$HOME/.config}/obs-studio/plugins/openstream-obs/bin/64bit/openstream-obs.so
```

System-wide install (requires root; the OBS system plugin dir is
auto-detected, e.g. `/usr/lib/obs-plugins`):

```sh
sudo ./tools/installer/install-openstream-plugin-linux.sh --system
```

Testing hook (touches nothing outside the given directory):

```sh
./tools/installer/install-openstream-plugin-linux.sh --dest-dir /tmp/os-install-test
```

The install is atomic (stage to a temp file in the destination, then
rename), replaces the canonical module, and removes only other-named stale
copies (`openstream-beta-obs.so`, `libopenstream-obs.so`, the legacy flat
per-user copy) after the new copy succeeds.

Uninstall: delete `openstream-obs.so` from the user or system plugin
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

- `cmake` configure of `obs-plugin` against system libobs/FFmpeg/Qt6 with
  no `OBS_ROOT`: passes.
- Full plugin build (`openstream-obs.so`) and native tests cover control
  shutdown, SIGPIPE, single-camera ownership, JSON parsing and source settings.
- `ldd` confirms linkage to system `libobs`, `libobs-frontend-api`,
  `libavformat`/`libavcodec`/`libavutil`/`libswscale`, Qt6 Network/Widgets.
- See [reliability audit](reliability-audit.md) for the final check results.
  No installation into the running user’s OBS configuration is part of validation.

What was NOT validated and remains a blocker for release claims:

- Loading `openstream-obs.so` inside a running OBS GUI on Linux.
- A physical phone streaming over Wi-Fi (discovery, reservation, SRT
  media, reconnect, controls).
- Any Flatpak/Snap OBS build, any distro other than the validation
  machine, or physical camera sessions.
- No performance measurements were taken; no latency/CPU claims are made.
