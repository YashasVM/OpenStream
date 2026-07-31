# V4-00 reproducible media smoke evidence

This is a one-second local encoder/remux smoke, not a throughput benchmark, soak, transport comparison, or proof of production recording behaviour. The generated media is deliberately not checked in; the commands below reproduce it from this commit and the output hashes identify the run.

## Run environment

- Commit before this evidence change: `8413d286c64f07e865f64b0bf3ffcdc30e1718b3`.
- Windows host with NVIDIA GeForce RTX 4050 Laptop GPU, driver `592.82`.
- FFmpeg `8.0.1-full_build-www.gyan.dev`, built with GCC `15.2.0`; its configured encoders include `h264_nvenc` and `hevc_nvenc`.
- Python test command used Python's active `python` executable.

## Exact commands

Run from the repository root in PowerShell:

```powershell
tools/stream-simulator/generate-media.ps1 -OutputDirectory .phase0-smoke/h264-4x30 -Streams 4 -Fps 30 -Seconds 1 -Codec h264
tools/stream-simulator/generate-media.ps1 -OutputDirectory .phase0-smoke/hevc-2x60 -Streams 2 -Fps 60 -Seconds 1 -Codec hevc
python tools/recording-validator/validate.py .phase0-smoke/h264-4x30/camera-01/segment-0000.mkv .phase0-smoke/h264-4x30/camera-02/segment-0000.mkv .phase0-smoke/h264-4x30/camera-03/segment-0000.mkv .phase0-smoke/h264-4x30/camera-04/segment-0000.mkv .phase0-smoke/hevc-2x60/camera-01/segment-0000.mkv .phase0-smoke/hevc-2x60/camera-02/segment-0000.mkv
```

Both generation commands succeeded and reported `h264_nvenc` and `hevc_nvenc`, respectively. The validator returned `ok: true`: each H.264 segment had 30 video packets and each HEVC segment had 60; all six had monotonic timestamps.

## Output SHA-256

| Output | SHA-256 |
|---|---|
| `h264-4x30/camera-01/segment-0000.mkv` | `2E646E8097531293BCF95060A2EE72B2D2BE0D62FE08E0EAE2DF7FE6C7893B2F` |
| `h264-4x30/camera-02/segment-0000.mkv` | `0DFFB96E0D0574C77FF52B6C273C00F399BF278E8EB236C5E030DB7A2C35D3C3` |
| `h264-4x30/camera-03/segment-0000.mkv` | `3FACD2BC802858B1D9B605F4CE3FEA3E824D1490A7AE0E23116788CCED1861A2` |
| `h264-4x30/camera-04/segment-0000.mkv` | `B43D9B3A164F20350B3F58195A1AEE2EAC4D114B042BF50E4C0721981CDD9129` |
| `hevc-2x60/camera-01/segment-0000.mkv` | `CE6BD82ACA46E2E6EA9CA781CE3F36F574E54FE88F715FE33F8C9E8EAFB3527F` |
| `hevc-2x60/camera-02/segment-0000.mkv` | `5128430D7E564DE192C980BD2DD762955921A4F72D9F763A0BE893B435DE16AA` |

Re-running can produce different binary hashes with different FFmpeg, driver, or encoder versions; validator success and the stated codec/packet counts are the acceptance observations.
