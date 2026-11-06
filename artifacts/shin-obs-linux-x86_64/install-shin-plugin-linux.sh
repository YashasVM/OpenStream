#!/usr/bin/env bash
# Installs the shin OBS plugin module on Linux.
# Mirrors tools/installer/Install-shinPlugin.ps1 semantics:
# replaces known plugin copies (canonical + legacy beta names) instead of
# leaving stale modules behind.
#
#   ./install-shin-plugin-linux.sh [--so PATH] [--system] [--dest-dir DIR]
#
# Default (no flags): per-user install, no root required:
#   ${XDG_CONFIG_HOME:-$HOME/.config}/obs-studio/plugins/shin-obs/bin/64bit/shin-obs.so
# --system: system-wide install into the detected OBS plugin dir
#   (e.g. /usr/lib/obs-plugins); requires root.
# --dest-dir DIR: install shin-obs.so into DIR instead of the user or
#   system plugin dir. Testing hook: nothing outside DIR is touched.
#
# The install is atomic: the module is staged to a temp file inside the
# destination directory and renamed over the target, so the installed
# library is never deleted before the new copy succeeds.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PLUGIN_SO=""
SYSTEM=0
DEST_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --so)
      PLUGIN_SO="${2:?--so needs a path}"
      shift 2
      ;;
    --so=*)
      PLUGIN_SO="${1#--so=}"
      shift
      ;;
    --system)
      SYSTEM=1
      shift
      ;;
    --dest-dir)
      DEST_DIR="${2:?--dest-dir needs a path}"
      shift 2
      ;;
    --dest-dir=*)
      DEST_DIR="${1#--dest-dir=}"
      shift
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "${SYSTEM}" -eq 1 && -n "${DEST_DIR}" ]]; then
  echo "ERROR: --system and --dest-dir are mutually exclusive." >&2
  exit 1
fi

# Packaged no-argument resolution: a sibling shin-obs.so next to this
# script (extracted tarball layout) wins over the repository build fallback.
if [[ -z "${PLUGIN_SO}" ]]; then
  if [[ -f "${SCRIPT_DIR}/shin-obs.so" ]]; then
    PLUGIN_SO="${SCRIPT_DIR}/shin-obs.so"
  else
    PLUGIN_SO="${SCRIPT_DIR}/../../obs-plugin/build/shin-obs.so"
  fi
fi
if [[ ! -f "${PLUGIN_SO}" ]]; then
  echo "ERROR: plugin module not found: ${PLUGIN_SO}" >&2
  echo "Build it first: ./build_plugin_linux.sh" >&2
  exit 1
fi

# Removes only other-named stale modules (never the install target itself,
# which is replaced atomically by rename below).
remove_stale_names() {
  local dir="$1"
  for name in shin-beta-obs.so libshin-obs.so; do
    if [[ -f "${dir}/${name}" ]]; then
      rm -f "${dir}/${name}"
      echo "Removed stale copy: ${dir}/${name}"
    fi
  done
}

# Atomic stage-then-rename inside the destination directory.
atomic_install() {
  local src="$1"
  local dir="$2"
  local target="${dir}/shin-obs.so"
  local tmp=""
  tmp="$(mktemp "${dir}/.shin-obs.so.XXXXXX")"
  install -m 0644 "${src}" "${tmp}"
  mv -f "${tmp}" "${target}"
  echo "Installed plugin: ${target}"
}

if [[ -n "${DEST_DIR}" ]]; then
  mkdir -p "${DEST_DIR}"
  atomic_install "${PLUGIN_SO}" "${DEST_DIR}"
  remove_stale_names "${DEST_DIR}"
elif [[ "${SYSTEM}" -eq 1 ]]; then
  candidates=()
  if command -v obs >/dev/null 2>&1; then
    obs_bin="$(readlink -f "$(command -v obs)")"
    candidates+=("$(dirname "${obs_bin}")/../lib/obs-plugins")
  fi
  candidates+=(
    /usr/lib/obs-plugins
    /usr/lib64/obs-plugins
    /usr/lib/x86_64-linux-gnu/obs-plugins
    /usr/local/lib/obs-plugins
  )
  dest_dir=""
  for candidate in "${candidates[@]}"; do
    if [[ -d "${candidate}" ]]; then
      dest_dir="${candidate}"
      break
    fi
  done
  if [[ -z "${dest_dir}" ]]; then
    echo "ERROR: no OBS system plugin dir found." >&2
    exit 1
  fi
  atomic_install "${PLUGIN_SO}" "${dest_dir}"
  remove_stale_names "${dest_dir}"
else
  config_home="${XDG_CONFIG_HOME:-${HOME}/.config}"
  dest_dir="${config_home}/obs-studio/plugins/shin-obs/bin/64bit"
  mkdir -p "${dest_dir}"
  atomic_install "${PLUGIN_SO}" "${dest_dir}"
  remove_stale_names "${dest_dir}"
  # Legacy flat per-user copy from early manual installs.
  legacy_flat="${config_home}/obs-studio/plugins/shin-obs.so"
  if [[ -f "${legacy_flat}" ]]; then
    rm -f "${legacy_flat}"
    echo "Removed stale copy: ${legacy_flat}"
  fi
fi

echo "Restart OBS Studio, then add a shin source."
