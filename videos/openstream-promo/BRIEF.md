---
workflow: product-launch-video
flow: automation
storyboard: no
message: "OpenStream 2 turns your Android phone into a native OBS camera — it works out of the box. Free and open source on GitHub."
destination: youtube
aspect: 1920x1080
language: en
length: 37.5s
angle: kill-the-capture-card
---

## Intent

A ~30s high-intensity, no-voiceover motion-graphics promo that tells people OpenStream
exists on GitHub and they can use it. Story: "No capture card. No cloud." problem slam →
phone becomes an OBS camera → pipeline flash → stat hits → GitHub CTA.
User: "clean, professional", "colors like beige", "high energy beat", "just create it, I don't care what you give me".

## Assets

- assets/obs-live-feed.png — real OBS screenshot of the phone feed live; proof beat.
- assets/obs-dock.png — real OBS camera-control dock screenshot; feature beat.

## Customizations

- High-energy music bed + SFX (whoosh, hits, risers, ticks) cut on the beat.
- Brand-new clean design system (beige/cream, ink, one accent) — NOT the website look.
- v2 (user, 2026-10-03): Apple-style sans (Inter; SF Pro is not licensable for this use), beige plus
  bright colours, SaaS product-launch feel, cool 3D graphics and smooth 3D camera moves.
- v2 assets: assets/obs-live.png (real OBS live feed + dock), assets/phone-app.jpg (Android app screen).

## Notes

- No website capture: user dislikes the current site look and colors.
- No voiceover, no captions.
- CTA: github.com/YashasVM/OpenStream

## v3 direction (user, 2026-10-03)

- "Forget the earlier guidance" — full creative control; premium, clean, minimal, few colours.
- Present the release as "OpenStream 2" that works out of the box.
- Hook must start low and build to the high-energy part; ~45s allowed, shorter if content is thinner (chose 40s).
- Use the real Android launcher logo (vectorised to assets/openstream-logo.svg).
- Keep every version: renders/openstream-promo-vN.mp4, committed.

## v4 direction (user, 2026-10-03)

- Loved v3's look. Start at a medium pace (not slow), step up to high energy in gears (not a smooth ramp,
  not instant), get fast toward the end, then go slow again on the GitHub star close.
- Animations were too slow and the beat too smooth in v3 — make everything energetic.
- Phone screenshot: assets/phone-live.jpg (Screenshot_20261003_092438_OpenStream.jpg).

## v5 direction (user, 2026-10-04)

- ~45s narrated SaaS-demo promo: "use your Android phone as an ultra-HQ OBS webcam, zero friction".
- Use the live website's design system (ink #0e0e0c, paper #f2ede4, orange #ff5b14, Unbounded + Inter).
- Male voiceover (Kokoro am_michael, 1.08x), smooth cursor, 3D phone + OBS window.
- Joke: 1080p60 not 4K because 4K over Wi-Fi is too much ("how routers retire early").
- End card URL: os.yash0.in. Built by tools/build_frames_v5.py + tools/make_music_v5.py.
- v4 root composition archived at archive/index-v4.html (its frames stay in compositions/frames/).

## v6 direction (user, 2026-10-04) — v5 rejected

- v5 felt flat and cringe; the 4K joke undercut the product. Never joke about limits or missing features.
- Energetic: upbeat music (HeyGen "Car Ad Energy", 120 BPM, starts at 10s so the drop hits "Meet OpenStream" at 0:06),
  energetic voice (HeyGen "Ewan – Bright & Energetic", 1.08x, word timestamps drive motion).
- Take inspiration from the site (ink / paper / orange, Unbounded + Inter), do NOT reproduce its layout or rotating badge.
- No real-room screenshots — feed is a generated creator shot (assets/feed-creator.png, codex image_gen); phone + OBS UI are designed.
- One continuous composition (tools/build_v6.py → index.html): lens dive, logo drop, cursor demo with camera chase,
  dive into the preview, extruded 3D type, Go-live climax. Final audio loudnorm'd to -14 LUFS after render.
- v5 entry archived at archive/index-v5.html.

## v7 direction (current request, 2026-10-04)

Use os.yash0.in itself in the film; preserve its visual identity and animate real website plates. Full creative discretion and direct finished-file delivery authorized. Professional smooth 60fps with music and voiceover. Instagram, YouTube and other social destinations.

Deliver 33.5s in independently composed 1920×1080 and 1080×1920 formats. HeyGen Ewan narrator, phrase captions, Car Ad Energy music, light transition SFX. Source generator: tools/build_v7.py. Landscape: index.html. Portrait: formats/portrait/index.html. Preserve all previous renders and archive v6 entry.

## v8 direction (latest correction)

Vertical website-only animation. Remove all custom phone props, added branding/title cards, and caption bands. Use actual captured website DOM, source CSS and original website marks only. Elements drop into their real page positions. A single 3D camera smoothly scrolls, tilts, and pushes into the website. Narration explains the product in website order over upbeat music. 39.5s, 1080×1920 at 60 fps. All further operations use the CLI at the user's request. Source: tools/build_website_v8.py; composition: formats/website-vertical/index.html.

## v9 revision (2026-10-04)

Keep the accepted vertical website animation. Remove opening headroom with a closer camera and compact hero spacing. Let feature rows fill orange with deterministic versions of the original hover effect, timed to narration. Extend the website footer with an orange “Star this on GitHub” panel and animated star, plus matching Ewan closing narration. 44 seconds, 1080×1920 at 60fps; source tools/build_website_v9.py, composition formats/website-vertical-v9/index.html. Preserve v8.
