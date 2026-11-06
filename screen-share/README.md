# Quick screen share to OBS

A separate, temporary Android screen-sharing helper. Requires Python 3 and
[scrcpy 3 or newer](https://github.com/Genymobile/scrcpy). Run it on the computer
running OBS. No relay or change to the OpenStream camera app is required.

1. Enable USB debugging in Android Developer options, connect a USB cable, and
   accept the phone's debugging prompt for your computer.
2. Run `python3 screen-share/share.py` from the repository root.
3. In OBS add **Window Capture**, select **OpenStream Screen**, and fit the source
   to your scene. On Wayland use the PipeWire screen capture picker to select that window.
4. Show the source during your demo. Hide it in OBS and close the mirror window
   when finished. Disable USB debugging when you no longer need it.

To show an app on a separate Android display:

```sh
python3 screen-share/share.py --app com.android.settings
```

Replace the package name with your app's package. This opens a separate app
instance/display; it does not mirror the current app's existing screen. Some apps
cannot run on secondary displays. Use whole-screen mode in that case. Whole-screen
mode includes notifications and everything you open, so enable Do Not Disturb first.
Protected video and secure apps may appear black.

Use `--serial DEVICE_SERIAL` if multiple Android devices are connected.
Audio plays through scrcpy when supported by the phone; capture that application
or desktop audio in OBS and check the mixer before going live. Avoid capturing the
same audio twice. This helper does not create or configure an OBS source for you.

For a desktop app, use OBS Window Capture directly. This helper supports Android,
not iPhone. Real phone capture, audio sync, and OBS must be checked on your hardware.

Check the launcher with `python3 -m unittest discover -s screen-share`.
