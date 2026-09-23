# OpenStream Set-Up Guide

This guide is the slower, screenshot-led path for installing OpenStream V1.0.1 on an Android phone and a Windows OBS Studio PC.

For native Linux, follow [Linux setup](linux.md). The same phone pairing and
per-source ownership rules apply on Linux and Windows.

For the fast technical version, use the [README quick start](../README.md#quick-start).

---

## What You Need

| Requirement | Details |
|---|---|
| Android phone | Android 10 or newer is recommended. The phone must support Camera2. |
| Windows PC | OBS Studio installed on Windows x64. |
| Same network | Phone and PC must be on the same Wi-Fi or LAN subnet. |
| Release files | V1.0.1 APK for the phone, installer EXE for the OBS plugin. |

> [!TIP]
> If discovery does not work, temporarily disable VPNs, guest Wi-Fi, and router client isolation.

---

## 1. Download the Release Files

Open the latest OpenStream V1.0.1 release and download:

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

New sources appear as `OpenStream Camera`. The plugin also registers the
published internal IDs `openstream_phone_v7_source` and
`openstream_phone_v8_source` as entries named `OpenStream Camera (V7
compatibility)` and `OpenStream Camera (V8 compatibility)`. This lets OBS
resolve those source IDs when reading scene data. Loading old scenes while
preserving their settings and receiving media has not yet been verified in
Windows or Linux OBS, so treat old-scene behavior as unconfirmed until those
checks are recorded.

If OBS still shows an older OpenStream source after installing V1.0.1, check these plugin locations and remove or replace stale copies:

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
3. Choose `OpenStream Camera` for a new source. The V7/V8 compatibility entries
   are for scene records using those published source IDs.
4. In `1. Camera`, keep automatic phone pairing selected for the first test.
5. Keep one OpenStream source. Use **Add Existing** to reuse it in other scenes; remove extra legacy camera sources.
6. Use `Refresh Phones` if your Android device appears after the properties window is already open.
7. Leave `3. Network & Pairing (Advanced)` collapsed unless discovery fails.
8. Click OK.

![OBS source screenshot](assets/setup/obs-source.svg)

The source can stay blank until a phone connects. That is normal.

---

## 5. Connect the Phone

1. Put the phone and PC on the same Wi-Fi network.
2. Open OpenStream on the phone.
3. Wait for the OBS device to appear.
4. Tap your OBS computer in the discovered devices list.
5. The phone camera should appear in OBS.

![Android connect screenshot](assets/setup/android-connect.svg)

If the phone does not find OBS, open the source’s **Network & Pairing (Advanced)** settings, enable **Receive manual connection from phone**, and apply. Then use manual connect on the phone. This uses the phone’s camera controls; remote controls in OBS require automatic pairing. Both devices still need direct LAN connectivity:

| Value | Default |
|---|---|
| OBS PC IP address | Your PC's LAN IP, for example `192.168.1.25` |
| SRT port | `9100` |
| Latency | `120 ms` |

The phone uses its own control endpoint on port `9101`; do not enter that port
as the media port. Manual receive mode is an advanced fallback: it makes the
phone call the OBS listener directly, while automatic pairing reserves the
selected phone before opening its SRT stream.

### Connect and disconnect a source

Each OBS source is one camera slot. In the source properties, choose a phone
under **1. Camera**, then click **Connect / Reconnect**. With **Automatic
pairing**, OBS reserves that phone and the phone shows the selected slot label.
The source status changes from `Waiting` to `Reserved` and then `Live` after
SRT media arrives. Use **Add Existing** when the same camera should appear in
another scene; creating another active source for the same camera is blocked.

To disconnect, stop or remove the source, or use the source's **Disconnect**
control when it is shown. OBS releases the reservation and the phone returns
to its available state. Disconnecting one source does not stop another phone.
If the phone is reserved but media has not arrived, disconnect still releases
the reservation; a short reconnect window may keep the same phone preferred
when the network briefly drops.

On Android, tap an OBS device in the discovered list to connect. The active
card shows the slot label and a **Disconnect** action. Tap it before selecting
another OBS slot. The phone's **Settings** screen also provides **Save** and
**Save and connect** for manual pairing; the saved host, port, latency and
listening port survive a disconnect and app restart.

---

## 6. Confirm Audio and Controls

In OBS:

1. Look for the OpenStream audio channel in the mixer.
2. Open the OpenStream source properties.
3. Use `2. Live Camera Controls`.
4. Try the zoom slider.
5. Try torch on/off if the selected phone camera supports it.
6. Switch rear/front camera from the source properties or phone UI.
7. Use `Identify Phone` to show a connection label on your phone.

---

## Troubleshooting

| Problem | Try |
|---|---|
| Installer cannot find OBS | Re-run it and choose the folder that contains `bin\64bit\obs64.exe`. |
| Windows blocks the EXE | Use `More info` then `Run anyway`, or use the manual zip install. |
| OpenStream Camera is missing in OBS | Confirm `openstream-obs.dll` is in one of the plugin folders above, remove stale older copies, then restart OBS. |
| Old OpenStream source is still visible | OBS may be loading an older all-users DLL from `C:\ProgramData\obs-studio\plugins\openstream-obs\bin\64bit\`. Replace it with the current plugin or remove it. |
| Phone cannot see OBS | Put both devices on the same Wi-Fi, disable VPNs, and check guest/client isolation. |
| Camera stays blank | Start with media port `9100`, latency `120 ms`, and one phone only. |
| Audio is missing | Grant microphone permission on Android and check the OBS mixer channel. |
| Stream stutters | Use 5 GHz or Wi-Fi 6, move closer to the router, and avoid congested networks. |

---

## Developer Notes

The Python receiver is a developer/debug path only; it is not part of the normal user workflow.

```powershell
python tools/openstream_receiver.py --port 9100 --latency-ms 120 --ffplay
```

Use it only when debugging SRT transport outside OBS.
