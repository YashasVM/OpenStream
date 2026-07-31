# V4 stream simulator

`generate.py` writes deterministic encoded-access-unit metadata for four 1080p30 or two 1080p60 sessions. It does not pretend synthetic payload sizes are a codec benchmark. Use `generate-media.ps1` to create a real H.264/HEVC MKV corpus through FFmpeg; software fallback requires `-AllowSoftwareFallback` and is clearly reported.

```powershell
python tools/stream-simulator/generate.py --streams 4 --fps 30 --seconds 10 --output run/aus.jsonl
tools/stream-simulator/generate-media.ps1 -OutputDirectory run/media -Streams 4 -Fps 30 -Seconds 10 -Codec h264
```

The media script first creates an Annex-B encoded source, then uses `-c copy` to remux it into segmented MKV. The intermediate elementary stream is deleted after successful remuxing. This makes the recording boundary explicit and keeps decode/re-encode out of the ISO step.
