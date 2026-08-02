# ETW copy-ledger corroboration

Run from an elevated Windows terminal. Keep the `.etl` outside Git and publish its SHA-256 beside the raw JSONL evidence.

```powershell
wpr -start GPU -filemode
# run one clean workload or the device-removal case
wpr -stop out/gpu-traces/clean.etl
Get-FileHash out/gpu-traces/clean.etl -Algorithm SHA256
```

The trace review must confirm the selected adapter LUID, VideoDecode and 3D/video-processing activity, no CPU upload/readback transfer in the normal frame path, and no activity on another adapter. A trace hash without a recorded review is not a pass.
