[CmdletBinding()]
param([Parameter(Mandatory)][string]$ZipPath)

$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ("openstream-package-" + [guid]::NewGuid())
try {
    $stage = Join-Path $root 'package'; $obs = Join-Path $root 'obs-studio'
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $stage
    New-Item -ItemType Directory -Force -Path (Join-Path $obs 'bin\64bit') | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $obs 'bin\64bit\obs64.exe') | Out-Null
    $programData = Join-Path $root 'ProgramData'; $appData = Join-Path $root 'AppData'
    foreach ($name in 'openstream-obs.dll','openstream-beta-obs.dll') {
        foreach ($path in @(
            (Join-Path $obs "obs-plugins\64bit\$name"),
            (Join-Path $programData "obs-studio\plugins\openstream-beta-obs\bin\64bit\$name"),
            (Join-Path $appData "obs-studio\plugins\openstream-beta-obs\bin\64bit\$name"))) {
            New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
            Set-Content -LiteralPath $path -Value legacy
        }
    }
    & (Join-Path $stage 'Install-OpenStreamBetaPlugin.ps1') -ObsInstallDir $obs -ProgramDataRoot $programData -AppDataRoot $appData
    $canonical = Join-Path $obs 'obs-plugins\64bit\openstream-beta-obs.dll'
    if (!(Test-Path $canonical) -or !(Test-Path (Join-Path $obs 'data\obs-plugins\openstream-beta-obs'))) { throw 'Canonical package layout was not installed.' }
    $remaining = Get-ChildItem -Path $obs,$programData,$appData -Filter 'openstream-obs.dll' -Recurse -ErrorAction SilentlyContinue
    if ($remaining) { throw "Legacy plugin survived migration: $($remaining.FullName -join ', ')" }
    Write-Host 'ZIP migration smoke test passed.'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
