---
format: 1920x1080
duration: 30s
message: "OpenStream turns your Android phone into a native OBS camera over local Wi-Fi — free and open source on GitHub."
arc: PAS → Hook → Agitation → Product intro → Mechanism → Proof → Stats → CTA
audience: streamers, creators and OBS users with an Android phone; devs browsing GitHub
mode: autonomous
music: original synthesized electronic track, 128 BPM, 16 bars = 30.0s (assets/bgm/openstream-128bpm.wav)
tempo: 128 BPM — beat 0.46875s, bar 1.875s; every frame boundary sits on a bar line
---

## Video direction

v2 (SaaS launch): warm beige ground (#F2EBDD) with drifting blurred colour blobs (orange, pink,
violet, blue, lime, yellow), Inter at 600–800 with tight tracking (Apple / SF-style), gradient
headline words (orange → pink → violet), white floating cards with soft shadows. Every frame is a
CSS-3D stage under a perspective camera that orbits, dollies and cranes (power2/3 inOut); objects
carry real depth (a layered 3D phone, a 3D app icon, tilted pipeline board, a stat carousel).
Every reveal lands on the 128 BPM grid; hard cuts between frames on bar lines. Frames are generated
by tools/build_frames.py. Real media: assets/obs-live.png, assets/phone-app.jpg.

## Frame 1 — Hook: the old way

- scene: "Capture card?" "Webcam app?" "Cloud relay?" slam in one per beat-pair and get struck through
- duration: 3.75s
- poster: 3.2s
- transition_in: cut
- status: animated
- blueprint: kinetic-type-beats
- voiceover: (none — music only; cues on beats 0, 2, 4, 6)
- sfx: whoosh @0.0, whoosh @0.9375, whoosh @1.875, glitch-3 @2.8125
- src: compositions/frames/01-hook.html

Scene 1 (0.0–0.94s): mono kicker "✱ TO GET YOUR PHONE INTO OBS" types on top-left; serif display "Capture card?" punches in centre (scale 1.15→1, y 40→0).
Scene 2 (0.94–1.88s): coral strike-line draws across it; it shoves up and dims; "Webcam app?" punches in below.
Scene 3 (1.88–2.81s): strike; "Cloud relay?" punches in.
Scene 4 (2.81–3.75s): strike the third; all three stack jitter on the beats, frame holds tension.

## Frame 2 — Agitation → turn

- scene: "None of it." huge serif; then "Just your phone." with a minimal phone outline dropping in
- duration: 3.75s
- poster: 3.3s
- transition_in: cut
- status: animated
- blueprint: compose
- voiceover: (none — snare-roll build; cues on beats 0, 4)
- sfx: impact-bass-2 @0.0, pop @1.875
- src: compositions/frames/02-turn.html

Scene 1 (0.0–1.88s): "None of it." display-cover slams in word-by-word (2 beats each).
Scene 2 (1.88–3.75s): line clears up; a CSS phone outline (rounded rect, camera dot) drops from top with a hard settle; "Just your phone." italic beside it; phone vibrates faster as the build rises.

## Frame 3 — Drop: product intro

- scene: OpenStream wordmark slams on the drop, coral ✱, "Android phone → OBS camera" index underneath
- duration: 3.75s
- poster: 2.5s
- transition_in: cut
- status: animated
- blueprint: compose
- voiceover: (none — the drop; cue on beat 0)
- sfx: impact-bass-1 @0.0, whoosh-short @1.875
- src: compositions/frames/03-drop.html

Scene 1 (0.0–0.5s): white flash-to-cream, "OpenStream" display-cover scales 1.6→1 with blur 20→0; coral ✱ scales 0.6→1.
Scene 2 (0.5–1.88s): hairline rules draw out left/right; mono index "ANDROID PHONE → OBS CAMERA · LOCAL WI-FI".
Scene 3 (1.88–3.75s): three tags pop in on beats: "No capture card" · "No cloud" · "Open source".

## Frame 4 — Mechanism: the pipeline

- scene: warm-navy code surface; five nodes Camera2 → MediaCodec → MPEG-TS → SRT → OBS light up one per beat, packets stream across
- duration: 5.625s
- poster: 4.8s
- transition_in: cut
- status: animated
- blueprint: compose
- voiceover: (none — cues on beats 0..11)
- sfx: click @0.9375, click @1.40625, click @1.875, click @2.34375, ping @2.8125, whoosh-short @3.75
- src: compositions/frames/04-pipeline.html

Scene 1 (0.0–0.94s): navy panel scales up from centre (0.9→1), title bar "openstream — pipeline" in mono.
Scene 2 (0.94–2.81s): nodes light up left→right one per beat; connector lines draw coral-free (cream).
Scene 3 (2.81–4.69s): packets (small squares) stream along the line on a loop; "AVC + AAC" and "SRT · LAN only" labels pop.
Scene 4 (4.69–5.63s): status strip types "● LIVE  1920×1080 · 30 fps · 12 Mbps".

## Frame 5 — Proof: real OBS

- scene: two real OBS screenshots slide in as hairline windows; callouts "Live in OBS", "Lens · Zoom · Torch", "Native source"
- duration: 5.625s
- poster: 4.5s
- transition_in: cut
- status: animated
- blueprint: compose
- voiceover: (none — cues on beats 0, 4, 8)
- asset_candidates: assets/obs-live.png, assets/phone-app.jpg
- focal: assets/obs-live.png
- roles: obs-live.png supporting (3D window), phone-app.jpg supporting (3D phone screen)
- sfx: whoosh @0.0, whoosh @0.9375, pop @1.875, pop @2.8125, pop @3.75
- src: compositions/frames/05-proof.html

Scene 1 (0.0–0.94s): left window (live feed) whips in from left with motion blur.
Scene 2 (0.94–1.88s): right window (dock) whips in from right, slightly rotated depth.
Scene 3 (1.88–4.69s): coral-dot callouts pop on beats: "Live in OBS", "Lens · zoom · torch", "Native source, no mirroring".
Scene 4 (4.69–5.63s): slow push-in on the left window.

## Frame 6 — Numbers

- scene: rapid stat swaps on beats: "12 Mbps" count-up, "0 bytes to the cloud", "8–50 Mbps", "$0 forever"
- duration: 3.75s
- poster: 3.4s
- transition_in: cut
- status: animated
- blueprint: dataviz-countup
- voiceover: (none — cues on beats 0, 2, 4, 6)
- sfx: ping @0.0, ping @0.9375, ping @1.875, ping @2.8125
- src: compositions/frames/06-numbers.html

Scene 1–4 (one per 0.9375s): number-hero figure + mono unit swaps with a vertical slot-machine roll; a hairline progress rule fills per beat.

## Frame 7 — CTA

- scene: "Your phone. Your OBS." serif; coral pill "★ github.com/YashasVM/OpenStream"; "Free & open source"
- duration: 3.75s
- poster: 3.0s
- transition_in: cut
- status: animated
- blueprint: compose
- voiceover: (none — final impact on beat 0, last hit on beat 4)
- sfx: impact-bass-1 @0.0, sparkle @0.9375
- src: compositions/frames/07-cta.html

Scene 1 (0.0–0.94s): "OpenStream" wordmark small top; "Your phone. Your OBS." slams in.
Scene 2 (0.94–1.88s): coral CTA pill expands from centre, GitHub URL types in.
Scene 3 (1.88–3.75s): mono "FREE · OPEN SOURCE · LOCAL-ONLY" fades up; ✱ pulses; final settle fade at tail.
