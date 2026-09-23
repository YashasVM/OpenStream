#!/usr/bin/env bash
# shin OBS Plugin Build Script for Linux.
# Configures, builds, tests, and optionally packages the OBS source plugin.
#
#   ./build_plugin_linux.sh [--package-only] [--install-user] [--install-system]
#
# Defaults mirror build_plugin.bat: OPENSTREAM_VERSION defaults to 1.0.1
# (keep in sync with obs-plugin/CMakeLists.txt, android/app/build.gradle.kts,
# and tools/installer/openstream-obs-plugin.iss).
#
# Dependencies (do not install system packages from here; install them first,
# see docs/linux.md):
#   cmake, g++, pkg-config, libobs-dev, libavformat-dev, libavcodec-dev,
#   libavutil-dev, libswscale-dev, qt6-base-dev
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="${SCRIPT_DIR}/obs-plugin"
BUILD_DIR="${OPENSTREAM_PLUGIN_BUILD_DIR:-${PLUGIN_DIR}/build}"
PACKAGE_DIR="${OPENSTREAM_PLUGIN_PACKAGE_DIR:-}"
OPENSTREAM_VERSION="${OPENSTREAM_VERSION:-1.0.1}"

PACKAGE_ONLY=0
INSTALL_USER=0
INSTALL_SYSTEM=0
for arg in "$@"; do
  case "${arg}" in
    --package-only) PACKAGE_ONLY=1 ;;
    --install-user) INSTALL_USER=1 ;;
    --install-system) INSTALL_SYSTEM=1 ;;
    *) echo "ERROR: unknown argument: ${arg}" >&2; exit 1 ;;
  esac
done

echo ""
echo "====================================================="
echo "  shin - OBS Plugin Builder (Linux)"
echo "====================================================="
echo ""

# Incremental CMake build: the build dir is configured in place and never
# deleted here, so repeated runs reuse cached configure results and object
# files. Pass OPENSTREAM_PLUGIN_BUILD_DIR to use a different build dir
# (e.g. /tmp/openstream-linux-build for validation builds).

for tool in cmake g++ pkg-config; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "ERROR: required tool '${tool}' was not found. See docs/linux.md." >&2
    exit 1
  fi
done

missing_pc=()
for module in libobs obs-frontend-api libavformat libavcodec libavutil libswscale Qt6Core Qt6Widgets; do
  if ! pkg-config --exists "${module}" 2>/dev/null; then
    missing_pc+=("${module}")
  fi
done
if [[ "${#missing_pc[@]}" -gt 0 ]]; then
  echo "ERROR: missing pkg-config modules: ${missing_pc[*]}. See docs/linux.md." >&2
  exit 1
fi
echo "OBS system ABI (no Windows version pin; see docs/linux.md):"
echo "  libobs $(pkg-config --modversion libobs)"
echo "  obs-frontend-api $(pkg-config --modversion obs-frontend-api)"
echo "  libavformat $(pkg-config --modversion libavformat)"
echo "  Qt $(qmake6 -query QT_VERSION 2>/dev/null || pkg-config --modversion Qt6Core)"
echo ""

if [[ "${PACKAGE_ONLY}" -eq 0 ]]; then
  echo "[1/4] Configuring CMake build (incremental)..."
  mkdir -p "${BUILD_DIR}"
  cmake -S "${PLUGIN_DIR}" -B "${BUILD_DIR}" \
    -DCMAKE_BUILD_TYPE=Release \
    "-DOPENSTREAM_VERSION=${OPENSTREAM_VERSION}"

  echo "[2/4] Building shin plugin..."
  cmake --build "${BUILD_DIR}" --config Release

  if [[ ! -f "${BUILD_DIR}/shin-obs.so" ]]; then
    echo "ERROR: Build output not found: ${BUILD_DIR}/shin-obs.so" >&2
    exit 1
  fi

  echo "[3/4] Running C++ contract tests (ctest)..."
  ctest --test-dir "${BUILD_DIR}" --output-on-failure
else
  if [[ ! -f "${BUILD_DIR}/shin-obs.so" ]]; then
    echo "ERROR: --package-only needs an existing build at ${BUILD_DIR}/shin-obs.so" >&2
    exit 1
  fi
fi

if [[ -n "${PACKAGE_DIR}" ]]; then
  echo "[4/4] Packaging plugin artifact..."
  mkdir -p "${PACKAGE_DIR}"
  STAGE_DIR="${PACKAGE_DIR}/shin-obs-linux-x86_64"
  rm -rf "${STAGE_DIR}"
  mkdir -p "${STAGE_DIR}"
  cp "${BUILD_DIR}/shin-obs.so" "${STAGE_DIR}/shin-obs.so"
  cp "${SCRIPT_DIR}/tools/installer/install-shin-plugin-linux.sh" "${STAGE_DIR}/"
  tar -czf "${PACKAGE_DIR}/shin-obs-linux-x86_64.tar.gz" -C "${PACKAGE_DIR}" "shin-obs-linux-x86_64"
  echo "Packaged: ${PACKAGE_DIR}/shin-obs-linux-x86_64.tar.gz"
else
  echo "[4/4] Packaging skipped (set OPENSTREAM_PLUGIN_PACKAGE_DIR to package)."
fi

if [[ "${INSTALL_USER}" -eq 1 ]]; then
  "${SCRIPT_DIR}/tools/installer/install-shin-plugin-linux.sh" --so "${BUILD_DIR}/shin-obs.so"
fi

if [[ "${INSTALL_SYSTEM}" -eq 1 ]]; then
  "${SCRIPT_DIR}/tools/installer/install-shin-plugin-linux.sh" --so "${BUILD_DIR}/shin-obs.so" --system
fi

echo ""
echo "====================================================="
echo "  SUCCESS! shin plugin built."
echo "====================================================="
echo ""
echo "  Plugin: ${BUILD_DIR}/shin-obs.so"
echo ""
echo "Restart OBS Studio, then add a shin source."
echo ""
