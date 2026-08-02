# V4 decoder/GPU decision spike

This directory contains benchmark probes, not the product compositor. The checked-in workload definition covers both codecs at four 1080p30 and two 1080p60. Media files are generated locally, hashed into raw results, and excluded from Git.

The hardware gate is deliberately fail-closed. Media Foundation output must expose `IMFDXGIBuffer`; FFmpeg output must be `AV_PIX_FMT_D3D11`. Both must resolve to `ID3D11Texture2D` on the requested adapter LUID. Software frames, cross-adapter resources, readback, upload, and `av_hwframe_transfer_data` reject a hardware run. `--allow-software-fallback` only records explicit operator consent; this probe still rejects software output and does not implement fallback.

## Build

Use an x64 Visual Studio 2022 developer shell. Configure the FFmpeg development package root as described by the CMake diagnostic.

```powershell
cmake -S spikes/gpu -B out/gpu-build -G "Visual Studio 17 2022" -A x64 -DOPENSTREAM_FFMPEG_ROOT=C:/ffmpeg-dev
cmake --build out/gpu-build --config Release
ctest --test-dir out/gpu-build -C Release --output-on-failure
```

## Benchmark

Generate the deterministic real-media corpus once, then run the full one-hour matrix. Generation uses NVENC unless the operator explicitly opts into a warned software encoder; software-generated corpus is valid input but its generation is not counted as a hardware-path measurement.

```powershell
spikes/gpu/run-benchmark.ps1 -CorpusRoot out/gpu-corpus -OutputDirectory out/gpu-results -GenerateCorpus -CorpusSeconds 10 -AdapterLuid <decimal-luid> -DurationSeconds 3600 -WarmupSeconds 60
```

The runner writes per-second CPU/private-memory/process-GPU samples, environment and driver fingerprints, queue declarations, exact corpus hashes, and probe summaries to `metrics.jsonl`. Capture an ETW trace alongside at least one clean and one device-removal run using the commands in `trace/README.md`; large ETL files remain external and are represented by hashes in checked-in evidence.
