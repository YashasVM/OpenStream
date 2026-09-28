#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/build_android_apk.sh [--release] [--install]

Builds a shareable APK. Debug builds use this machine's Android debug key and
an automatically increasing version code. --release requires the four
OPENSTREAM_RELEASE_* signing variables used by the release workflow. Use it
to update an app installed from the public release.

--install runs adb install -r after building. Android only updates an installed
app when the application ID and signing certificate match and the new version
code is at least as high. A different debug key or a debug/release switch
requires the original signing key; uninstalling removes the installed app data.
EOF
}

release=false
install=false
for arg in "$@"; do
  case "$arg" in
    --release) release=true ;;
    --install) install=true ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$release" == true ]]; then
  variant=Release
  apk="$repo_dir/android/app/build/outputs/apk/release/app-release.apk"
  echo "Building release APK with the configured release signing key."
else
  variant=Debug
  apk="$repo_dir/android/app/build/outputs/apk/debug/app-debug.apk"
  echo "Building debug APK. It can update only an app signed with this machine's debug key."
fi

(cd "$repo_dir/android" && ./gradlew ":app:assemble$variant")
echo "APK: $apk"

if [[ "$install" == true ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb is required for --install (install Android SDK platform-tools)." >&2
    exit 1
  fi
  if output="$(adb install -r "$apk" 2>&1)"; then
    echo "$output"
  else
    echo "$output" >&2
    case "$output" in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) echo "The installed app has a different signing key. Build with its original key." >&2 ;;
      *INSTALL_FAILED_VERSION_DOWNGRADE*) echo "The installed app has a higher version code. Build a newer APK." >&2 ;;
    esac
    exit 1
  fi
fi
