# V4 Windows build foundation

This is the reproducible Windows x64 foundation for the standalone V4 engine,
Studio, and native decision probes. It deliberately contains no OBS dependency
and no product runtime feature.

## Pinned inputs

- Visual Studio 2022 MSVC v143 with the Windows 11 SDK.
- CMake 3.30 or newer.
- vcpkg tag `2026.06.24`, commit
  `cd61e1e26a038e82d6550a3ebbe0fbbfe7da78e3`.
- `nlohmann-json` 3.12.0 at port revision 2 from that baseline.
- Dynamic MSVC runtime and the `x64-windows` triplet.

The shared preset disables MSBuild file-access tracking. On the target Windows
11 build, the tracker can leave compiler processes suspended before CMake's
compiler check; CMake still supplies explicit dependencies to MSBuild, and CI
uses the same setting so the workaround stays continuously verified.

Later tasks add transport, FFmpeg, UI, and packaging dependencies only after
their ADRs select them. The engine must never link OBS.

## Clean local build

Run these commands from a normal PowerShell terminal at the repository root.
The bootstrap refuses to reuse a checkout at a different commit.

```powershell
cmake/bootstrap-vcpkg.ps1
$env:VCPKG_ROOT = (Resolve-Path out/vcpkg).Path
cmake --preset windows-x64-debug
cmake --build --preset windows-x64-debug
ctest --preset windows-x64-debug
cmake/write-third-party-notices.ps1 -InstalledDirectory out/vcpkg_installed/windows-x64-debug
```

For a release-equivalent foundation check, replace `debug` with `release` in
the final four commands. The configure step acquires only dependencies declared
in `vcpkg.json` and stores them under ignored `out/vcpkg_installed/`.

Generated provenance is written to `out/provenance/dependencies.json` and
`out/provenance/THIRD_PARTY_NOTICES.txt`. The JSON records the exact vcpkg
baseline, resolved package versions, architectures, license SHA-256 values, and
SHA-256 values for the checked-in manifest and presets.

`CMakeUserPresets.json.example` is optional IDE convenience. Copy it to the
ignored `CMakeUserPresets.json` only when Visual Studio needs a persistent local
`VCPKG_ROOT`; command-line builds do not require it.
