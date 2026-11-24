#!/usr/bin/env bash
set -euo pipefail
# Pinned upstream source and NDK make the checked-in ABI archives rebuildable.
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.1.12297006}"
work_dir="${OPENSTREAM_SRT_BUILD_DIR:-$(mktemp -d)}"
commit=899348d8318eb9a3c5a5b6ec43c4a1114288773a
mkdir -p "$work_dir/source"
curl -fLsS "https://codeload.github.com/Haivision/srt/tar.gz/$commit" -o "$work_dir/srt.tar.gz"
(cd "$work_dir" && echo '82050d96a1a55d54b423fed4ad80ddb6f30b31bf33e317ffd6726c91ee9a6170  srt.tar.gz' | sha256sum -c -)
tar -xzf "$work_dir/srt.tar.gz" -C "$work_dir/source" --strip-components=1
destination="$repo_dir/android/app/src/main/cpp/third_party/srt"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  cmake -S "$work_dir/source" -B "$work_dir/$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DENABLE_APPS=OFF \
    -DENABLE_TESTING=OFF -DENABLE_ENCRYPTION=OFF \
    -DCMAKE_INSTALL_PREFIX="$work_dir/install-$abi"
  cmake --build "$work_dir/$abi" --parallel "${OPENSTREAM_BUILD_JOBS:-4}"
  cmake --install "$work_dir/$abi"
  mkdir -p "$destination/lib/$abi"
  cp "$work_dir/install-$abi/lib/libsrt.a" "$destination/lib/$abi/libsrt.a"
done
rm -rf "$destination/include/srt"
cp -R "$work_dir/install-arm64-v8a/include/srt" "$destination/include/srt"
cp "$work_dir/source/LICENSE" "$destination/LICENSE"
echo "Rebuilt SRT 1.5.7 for all four Android ABIs in $destination"
