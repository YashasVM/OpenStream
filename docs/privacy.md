# OpenStream privacy

This page describes behavior visible in the repository source and current product documentation. It does not verify the contents or network behavior of the published v1.0.1 APK or OBS packages.

## Camera and microphone

The Android app requests camera and microphone access to capture video and audio. Android controls these permissions. Deny either permission if you do not want the app to use that input.

## Media and network traffic

The documented product path sends camera video and microphone audio from the phone to OBS over the local network. Discovery and camera control also use the local network. Both devices need network access to each other for the documented workflow.

The repository does not document a cloud account or cloud media relay. This does not establish that the published artifacts, build services, website host, network equipment, or OBS collect no data. Those systems have separate privacy behavior.

## Diagnostics and reports

The source and documentation include local app and OBS diagnostics. This repository does not document automatic submission of diagnostics to a project server. Do not share logs or screenshots publicly until you have removed addresses, device identifiers, tokens, and other personal information.

## Website

The website is hosted separately from the Android app and OBS plugin. Its hosting provider may process request data under its own terms. This repository does not define the host's retention policy.
