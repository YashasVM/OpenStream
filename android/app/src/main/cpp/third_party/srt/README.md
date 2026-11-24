# Android SRT

The four checked-in static archives and public headers are SRT 1.5.7, built
from upstream commit `899348d8318eb9a3c5a5b6ec43c4a1114288773a` with NDK
27.1.12297006. Run `tools/build_android_srt.sh` from any directory to rebuild
all supported ABIs. The script verifies the source archive SHA-256 and builds
position independent static code. Final shared-library 16 KB page alignment
remains set in the app's CMakeLists.txt.

Encryption remains disabled, matching the existing bundled transport. The app
currently has no SRT passphrase contract. Do not advertise encrypted streaming;
adding it requires a crypto backend, packaging and explicit sender/receiver
configuration rather than enabling a flag alone.

Sources:
- https://github.com/Haivision/srt/releases/tag/v1.5.7
- https://github.com/Haivision/srt/blob/v1.5.7/docs/API/statistics.md
- https://developer.android.com/reference/android/media/MediaCodec#PARAMETER_KEY_VIDEO_BITRATE
