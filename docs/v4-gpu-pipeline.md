# V4 GPU pipeline prototype

## Adapter rule

Choose one DXGI adapter before a session and keep decode, processing, composition, virtual-camera surfaces, and programme encode on it. Never split frames between Intel and NVIDIA during a recording.

## Candidate paths

```text
Media Foundation: compressed AU -> D3D11-aware MFT -> NV12 ID3D11Texture2D
FFmpeg:          compressed AU -> AV_PIX_FMT_D3D11 -> AVFrame holding D3D11 texture
Both:            shared NV12 textures -> video processor/shader -> one 2x2 surface
Programme:       compositor texture -> one NVENC session (optional)
```

The prototype must reject a hardware backend when its advertised output becomes a software pixel format or when `av_hwframe_transfer_data` appears in the normal path. Software fallback is explicit, warned, and excluded from zero-copy results.

The Media Foundation proof requires each output sample to expose `IMFDXGIBuffer`. The FFmpeg proof requires `AV_PIX_FMT_D3D11`. The compositor binds decoder-owned `ID3D11Texture2D` resources and reports adapter LUID plus explicit CPU upload, readback, and copy counters. CPU counters are corroborated with PIX or ETW/GPUView for at least one clean and one impaired run.

## Copy ledger template

| Pipeline boundary | Expected copies | Verification | Result |
|---|---:|---|---|
| packet -> decoder input | payload copy into bounded packet slab only | allocation/copy counters | NOT_RUN |
| decoder -> D3D11 texture | 0 CPU frame copies | texture-backed output inspection | NOT_RUN |
| decoder texture -> multiview | 0 CPU copies; GPU process allowed | graphics trace | NOT_RUN |
| multiview -> programme | 0 CPU copies | shared texture identity/trace | NOT_RUN |
| programme -> NVENC | 0 CPU copies | encoder input resource trace | NOT_RUN |
| ISO packet -> muxer | compressed payload write only | codec/hash validation | NOT_RUN |

## Benchmark command probes

Use `ffmpeg -hwaccels`, `ffmpeg -decoders`, and `ffmpeg -encoders` to record capability. Exercise D3D11VA output with `-hwaccel d3d11va -hwaccel_output_format d3d11`; any filter that forces a software format invalidates the run. Native Media Foundation and compositor probes require a C++20 Windows harness and GPU capture in a later commit on this spike branch; FFmpeg CLI capability alone is not a decoder selection.

## Decoder decision

No decoder is selected until both backends decode the identical four-stream corpus for one hour, expose D3D11 textures, pass the copy ledger, and report CPU/GPU/memory. Direct NVDEC is evaluated only if those results show a material deficit.
