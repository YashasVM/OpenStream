# OpenStream Set-Up Guide

This guide is the slower, screenshot-led path for installing OpenStream V2 on an Android phone and a Windows OBS Studio PC.

For the fast technical version, use the [README quick start](../README.md#quick-start).

---

## What You Need

| Requirement | Details |
|---|---|
| Android phone | Android 10 or newer is recommended. The phone must support Camera2. |
| Windows PC | OBS Studio installed on Windows x64. |
| Same network | Phone and PC must be on the same Wi-Fi or LAN subnet. |
| Release files | V2 APK for the phone, installer EXE for the OBS plugin. |

> [!TIP]
> If discovery does not work, temporarily disable VPNs, guest Wi-Fi, and router client isolation.

---

## 1. Download the Release Files

Open the latest OpenStream V2 release and download:

| File | Install on | Use |
|---|---|---|
| [`openstream-android.apk`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-android.apk) | Android phone | Installs the camera app. |
| [`openstream-obs-plugin-installer-windows-x64.exe`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-plugin-installer-windows-x64.exe) | Windows PC | Installs the OBS plugin automatically. |
| [`openstream-obs-windows-x64.zip`](https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-windows-x64.zip) | Windows PC | Manual fallback package. |

![Release downloads screenshot](assets/setup/release-downloads.svg)

> [!NOTE]
> The GitHub page also shows `Source code` downloads. Those are for developers. Most users only need the APK and installer EXE.

---

## 2. Install the Android App

1. Move `openstream-android.apk` to your Android phone.
2. Open the APK from your Downloads app or file manager.
3. If Android blocks the install, allow installs from that app when prompted.
4. Open `OpenStream`.
5. Allow camera and microphone permissions.

The app should open directly into a camera preview.

---

## 3. Install the OBS Plugin on Windows

1. Close OBS Studio.
2. Run `openstream-obs-plugin-installer-windows-x64.exe`.
3. Accept the Windows administrator prompt.
4. Keep the OBS folder as `C:\Program Files\obs-studio` unless you installed OBS somewhere else.
5. Finish the installer.
6. Reopen OBS Studio.

![Windows installer screenshot](assets/setup/windows-installer.svg)

The installer copies `openstream-obs.dll` into:

```text
C:\Program Files\obs-studio\obs-plugins\64bit\
```

> [!IMPORTANT]
> Close OBS before installing or replacing the plugin. Windows can keep the old DLL loaded while OBS is open.

### Upgrading from an Older OpenStream Plugin

OpenStream V2 appears in OBS as `OpenStream V8`. Existing scene sources created with `OpenStream V7` are still supported, but the saved source names may remain `OpenStream V7` until you rename them in OBS.

If OBS still shows an older OpenStream source after installing V2, check these plugin locations and remove or replace stale copies:

```text
C:\Program Files\obs-studio\obs-plugins\64bit\openstream-obs.dll
C:\ProgramData\obs-studio\plugins\openstream-obs\bin\64bit\openstream-obs.dll
%APPDATA%\obs-studio\plugins\openstream-obs\bin\64bit\openstream-obs.dll
```

### Manual Plugin Install

Use this only if the installer EXE is blocked or you want to inspect the files first.

1. Download `openstream-obs-windows-x64.zip`.
2. Extract the zip.
3. Right-click `install-openstream-plugin.bat`.
4. Choose `Run as administrator`.
5. Restart OBS Studio.

You can also copy the DLL yourself:

```text
openstream-obs.dll -> C:\Program Files\obs-studio\obs-plugins\64bit\openstream-obs.dll
```

---

## 4. Add OpenStream in OBS

1. Open OBS Studio.
2. In `Sources`, click `+`.
3. Choose `OpenStream V8`.
4. Keep automatic phone selection enabled for the first test.
5. Give the source a production label such as `Main CAM` or `Desk CAM`.
6. Click OK. The source appears as a camera-slot card in **OpenStream Cameras**.
7. If the dock is hidden, enable it from OBS's **Docks** menu.

![OBS source screenshot](assets/setup/obs-source.svg)

The card shows **Waiting** until a phone connects. This is normal: the status,
primary action, and available camera controls update as the slot moves through
pairing, connecting, live, or recovery states. Source properties remain
available for setup and diagnostics; manual network fields are under
**Advanced**.

---

## 5. Connect the Phone

1. Put the phone and PC on the same Wi-Fi network.
2. Open OpenStream on the phone.
3. Allow camera and microphone access. If access was permanently denied, use
   the app's **Open settings** action and enable it in Android system settings.
4. In **Available OBS slots**, wait for a slot such as `CAM A`, `Main CAM`, or
   `Backup cam` to appear.
5. Tap an available slot to reserve it and connect.
6. Watch the status progress from connecting to live. The phone camera should
   appear in OBS.

![Android connect screenshot](assets/setup/android-connect.svg)

If no slots appear, use **Refresh** and follow the on-screen Wi-Fi checks. For
networks that block discovery, open **Settings > Connection**, enable manual
configuration, and enter:

| Value | Default |
|---|---|
| OBS PC IP address | Your PC's LAN IP, for example `192.168.1.25` |
| SRT port | `9000` |
| Latency | `120 ms` |

---

## 6. Confirm Audio and Controls

In the **OpenStream Cameras** dock:

1. Look for the OpenStream audio channel in the mixer.
2. Find the card whose status is **Live**.
3. Try the zoom control.
4. Try torch on/off if the selected phone camera supports it.
5. Switch rear/front camera from the dock or phone UI.
6. Use **Identify** to show the slot label on the connected phone.

Controls that the selected phone or lens cannot perform remain visibly
disabled. A command that is still running shows progress on its card; failures
appear inline and can be retried without reopening source properties.

---

## Troubleshooting

| Problem | Try |
|---|---|
| Installer cannot find OBS | Re-run it and choose the folder that contains `bin\64bit\obs64.exe`. |
| Windows blocks the EXE | Use `More info` then `Run anyway`, or use the manual zip install. |
| OpenStream V8 is missing in OBS | Confirm `openstream-obs.dll` is in one of the plugin folders above, remove stale older copies, then restart OBS. |
| Old OpenStream source is still visible | OBS may be loading an older all-users DLL from `C:\ProgramData\obs-studio\plugins\openstream-obs\bin\64bit\`. Replace it with the V2 DLL or remove it. |
| Phone cannot see OBS | Put both devices on the same Wi-Fi, disable VPNs, and check guest/client isolation. |
| Slot says Busy | Another phone owns that slot. Choose an available card or stop/release the current phone first. |
| Camera stays blank | Use **Retry** on the slot card. For manual setup, start with port `9000`, latency `120 ms`, and one phone only. |
| Audio is missing | Grant microphone permission on Android and check the OBS mixer channel. |
| Stream stutters | Use 5 GHz or Wi-Fi 6, move closer to the router, and avoid congested networks. |

---

## Developer Notes

The Python receiver is a developer/debug path only; it is not part of the normal user workflow.

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

Use it only when debugging SRT transport outside OBS.
