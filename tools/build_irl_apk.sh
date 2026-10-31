#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v java >/dev/null 2>&1; then
    java_bin="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
  elif [[ -x "$HOME/.local/share/mise/installs/java/temurin-17/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-17"
  else
    echo "Java 17+ is required. Set JAVA_HOME or add java to PATH." >&2
    exit 1
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "Android SDK not found. Set ANDROID_HOME to your SDK directory." >&2
  exit 1
fi
cd "$repo_dir/android"
./gradlew --configure-on-demand :irl:testDebugUnitTest :irl:lintDebug :irl:assembleDebug
printf 'APK: %s\n' "$repo_dir/android/irl/build/outputs/apk/debug/irl-debug.apk"
