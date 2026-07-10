# OpenStream V2 Beta

OpenStream V2 is focused on making the Android-to-OBS setup easier to understand and safer to upgrade. It includes the refreshed OBS source UI, V8 source naming, V7 scene compatibility, Android camera streaming, and direct install downloads for both surfaces.

## What's New

| Area | Update |
|---|---|
| OBS setup | Source properties now follow `1. Camera Slot`, `2. Live Camera Controls`, and `3. Network & Pairing (Advanced)`. |
| Upgrade path | Existing `OpenStream V7` scene sources keep loading through a compatibility source id. |
| Pairing copy | OBS now explains the phone-side slot selection flow instead of leading with SRT details. |
| Setup docs | The walkthrough now includes stale-plugin checks for Program Files, ProgramData, and per-user OBS plugin folders. |

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
4. Add an `OpenStream V8` source.
5. Open the Android app on the same Wi-Fi network and tap the discovered OBS slot.

For the screenshot walkthrough, read [`docs/set-up.md`](https://github.com/YashasVM/OpenStream/blob/main/docs/set-up.md).

## Notes

- Windows OBS plugin only.
- Existing source names may still say `OpenStream V7`; rename them in OBS if you want the scene label to match V2.
- Use 5 GHz or Wi-Fi 6 for best results.
- Keep both devices on the same subnet.
- This is still beta software; please report issues on GitHub.
