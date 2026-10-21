# OpenStream-IRL

OpenStream-IRL is a separate Android camera app that sends its picture and microphone to an OBS Browser Source. It installs alongside OpenStream as `dev.openstream.irl`. Your Linux box stores and relays the video through Tailscale. No cloud storage account, credit card, port forwarding, or streaming-provider keys are needed.

The phone records H.264/AAC into short MPEG-TS segments. Closed segments wait on phone storage until the relay acknowledges them. A network change retries the same upload rather than discarding the camera session. The relay stores ordered segments on disk and serves an HLS playlist. The OBS receiver buffers 6–30 seconds before playback.

This is an initial build for real-phone feedback. Build checks and synthetic playback do not establish handoff reliability, audio sync, or thermal behavior on a particular phone.

## Build the Android app

From the repository root, run:

```sh
tools/build_irl_apk.sh
```

The APK is `android/irl/build/outputs/apk/debug/irl-debug.apk`. Install it on your Android phone. It has its own application ID and cannot replace the original OpenStream app.

## Run the relay on the Linux box

The deployment target is `yvm@192.168.1.8`. It already runs Node 22 and Tailscale. From `openstream-irl/`, install dependencies and deploy:

```sh
npm ci
npm run build
./deploy-linux-box.sh
```

The script installs the app under `~/OpenStream-IRL`, its private data under `~/.local/share/openstream-irl`, and its access key under `~/.config/openstream-irl`. A user systemd service runs the relay and restarts it after a crash.

Tailscale Serve exposes the relay privately at:

```text
https://linux-yvm.tailddbb68.ts.net:8443
```

The existing service on port 443 stays intact. The phone and OBS computer must join the Linux box's tailnet and keep Tailscale connected, including on mobile data. The Linux box must remain powered on and connected to the internet.

If deployment reports that Tailscale Serve needs root permission, run this once on the Linux box:

```sh
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8796
```

The relay service can run before this command succeeds, but the phone cannot reach it until the HTTPS route is enabled.

Read the relay access key on the Linux box with:

```sh
cat ~/.config/openstream-irl/admin-token
```

Enter that key on the pairing page. Keep it private. Do not paste it into an issue, screenshot, or chat.

For local development, run `npm run dev`. The Android sender requires HTTPS; localhost HTTP is for desktop development.

## Pair the phone and OBS

1. Connect Tailscale on your phone and OBS computer, then open the private relay URL.
2. Enter the relay access key, create a connection, and save both private links. Closing the page loses its displayed links.
3. On the phone, open the camera pairing link in OpenStream-IRL or paste it into the app. Pairing remains saved on the phone.
4. Grant camera and microphone access, then start capture. Keep the app open and the screen unlocked.
5. In OBS, add a Browser Source. Paste the private receiver URL and set its dimensions to 1920 × 1080.
6. Enable **Control audio via OBS** in that source. If playback needs a click, open **Interact** and select **Play with audio**.
7. Start with a 10-second recovery delay. Change the slider on the pairing page and copy the updated receiver URL to change the delay.

OBS remains your broadcaster. Use scenes, overlays, audio controls, and streaming destinations in OBS as usual. Rotate the source in OBS if the phone's native sensor orientation appears sideways.

The camera pairing link authorizes publishing. The receiver URL authorizes viewing. Neither belongs in a shared OBS scene collection or public issue report.

## Check it on your phone

Start with Wi-Fi and confirm picture and microphone audio in OBS. Walk out of Wi-Fi coverage with mobile data enabled, then return. Check whether playback stays continuous within the chosen delay and whether audio remains aligned.

Repeat with a longer outage and confirm uploads recover without pairing again. Watch the queued data indicator. Stop capture and confirm that it stops. Restart using the saved pairing and existing OBS source.

For feedback, report the phone model, Android version, app version, bitrate, delay, approximate outage length, queue indicator, and what appeared in OBS. Exclude private links and keys.

## Current limits

- The phone app must stay open with its screen unlocked. Background and locked-screen capture are not implemented.
- Recovery is bounded by phone storage, relay retention, and playback delay. An outage longer than the buffered picture can freeze playback.
- Uploading a backlog needs more bandwidth than the live capture bitrate. The initial implementation uses a selected bitrate rather than automatic adaptation.
- Relay reachability and throughput depend on the Linux box and Tailscale's network path. A relayed Tailscale connection can reduce throughput.
- Video uses native camera sensor orientation. Automatic pixel rotation and front-camera mirroring are pending.
- Remote OBS camera-control commands are not implemented. Local phone controls and OBS scene/audio controls are available.
- The relay keeps a bounded rolling window and removes idle video. Saved pairing credentials remain valid. It is not a permanent recording archive.

Implementation status is at [a.yash0.in/irl](https://a.yash0.in/irl). Phone handoff, lip-sync, and long-session checks remain pending until real-device feedback.
