# OpenStream V2.1 Beta

OpenStream V2.1 makes the Android-to-OBS workflow faster, clearer, and safer to upgrade. It includes the native OBS control dock, smooth real-time zoom, lower-latency media handling, stronger reconnect behavior, streamlined source naming, and direct install downloads for both platforms.

## What's New

| Area | Update |
|---|---|
| OBS controls | The dock keeps connection, lens, torch, identify, and zoom controls beside the preview. Zoom responds continuously while the slider moves. |
| Streaming | Bounded queues, stale-frame dropping, tuned SRT behavior, and asynchronous Android sends reduce avoidable buffering. |
| Connectivity | Faster failure detection, clearer slot pairing, and reservation-aware reconnects make recovery more predictable. |
| Upgrade path | Existing scene sources keep loading through compatibility source IDs, while the installer removes known stale plugin copies. |
| Product polish | OBS now displays the product as `OpenStream`, with aligned setup, release, and update documentation. |

## Downloads

| File | Use |
|---|---|
| `openstream-android.apk` | Install this on your Android phone. |
| `openstream-android.apk.sha256` | Verify the downloaded APK before installation. |
| `openstream-obs-plugin-installer-windows-x64.exe` | Recommended Windows installer for the OBS plugin. |
| `openstream-obs-windows-x64.zip` | Manual plugin package with DLL and install scripts. |

> [!NOTE]
> The Android APK is release-signed and includes SHA-256 metadata. Public releases fail instead of publishing a debug-signed fallback when signing inputs are unavailable.

## Install

1. Install the APK on your Android phone.
2. Run the OBS plugin installer on your Windows OBS PC.
3. Restart OBS Studio.
4. Add an `OpenStream` source.
5. Open the Android app on the same Wi-Fi network and tap the discovered OBS slot.

For the screenshot walkthrough, read [`docs/set-up.md`](https://github.com/YashasVM/OpenStream/blob/main/docs/set-up.md).

## Notes

- Windows OBS plugin only.
- Existing user-assigned source names are preserved during upgrades and can be renamed normally in OBS.
- Use 5 GHz or Wi-Fi 6 for best results.
- Keep both devices on the same subnet.
- This is still beta software; please report issues on GitHub.
