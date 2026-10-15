# OpenStream promo video

`openstream-promo.mp4` is a 32-second, 1920×1080, 60 fps launch promo with motion blur and its own soundtrack.

- `openstream-promo.html` is the built page. Open it in a browser to scrub through the video and play it with sound.
- `src.html` is the editable source. `build.py` inlines the fonts (Inter, IBM Plex Mono) and the CC0 sound effects. `render.mjs` renders the page to MP4 with Playwright and ffmpeg.

To rebuild: `python3 build.py && node render.mjs openstream-promo.html openstream-promo.mp4 60 4 4`

Made with the explainer skills from [Barty-Bart/motion-graphics](https://github.com/Barty-Bart/motion-graphics) (MIT).
