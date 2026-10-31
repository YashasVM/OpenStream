# Live captions

Run from this directory:

```sh
python3 -m http.server 8787 --bind 127.0.0.1
```

Open http://localhost:8787 in Chrome, click Start microphone, and allow microphone access. Use Full screen for large captions. The display retains the latest 55 completed words. Stop releases the microphone. No installation or API key is needed.

This captions the microphone inside the browser window, not an always-on-top overlay over other applications. Browser speech recognition may send audio to its provider and require internet. Nothing is saved by this app.
