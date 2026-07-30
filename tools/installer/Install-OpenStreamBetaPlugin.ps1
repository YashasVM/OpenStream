[CmdletBinding()]
param(
    [string]$ObsInstallDir = $env:OPENSTREAM_OBS_INSTALL,
    [string]$ProgramDataRoot = $env:ProgramData,
    [string]$AppDataRoot = $env:APPDATA
)

$ErrorActionPreference = "Stop"
$legacyNames = @("openstream-obs.dll", "openstream-beta-obs.dll")

function Test-ObsInstallDir([string]$Path) {
    (Test-Path -LiteralPath (Join-Path $Path "bin\64bit\obs64.exe")) -or
        (Test-Path -LiteralPath (Join-Path $Path "bin\64bit\obs.exe"))
}
function Find-ObsInstallDir {
    $candidates = @($ObsInstallDir, "$env:ProgramFiles\obs-studio", "${env:ProgramFiles(x86)}\obs-studio")
    foreach ($candidate in $candidates | Where-Object { $_ } | Select-Object -Unique) {
        if (Test-ObsInstallDir $candidate) { return $candidate }
    }
    throw "OBS Studio was not found. Pass -ObsInstallDir with the OBS installation directory."
}
function Get-OpenStreamLegacyPaths([string]$ObsRoot) {
    $roots = @(
        (Join-Path $ObsRoot "obs-plugins\64bit"),
        (Join-Path $ProgramDataRoot "obs-studio\plugins\openstream-beta-obs\bin\64bit"),
        (Join-Path $ProgramDataRoot "obs-studio\plugins\openstream-obs\bin\64bit"),
        (Join-Path $AppDataRoot "obs-studio\plugins\openstream-beta-obs\bin\64bit"),
        (Join-Path $AppDataRoot "obs-studio\plugins\openstream-obs\bin\64bit")
    ) | Where-Object { $_ }
    foreach ($root in $roots) { foreach ($name in $legacyNames) { Join-Path $root $name } }
}

if (Get-Process -Name obs64,obs -ErrorAction SilentlyContinue) {
    throw "OBS Studio is running. Close OBS completely before installing OpenStream."
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceDll = Join-Path $scriptDir "openstream-beta-obs.dll"
$sourceData = Join-Path $scriptDir "data"
if (!(Test-Path -LiteralPath $sourceDll) -or !(Test-Path -LiteralPath $sourceData)) {
    throw "Extract the complete OpenStream package; openstream-beta-obs.dll and data are required."
}
$obsRoot = Find-ObsInstallDir
$destination = Join-Path $obsRoot "obs-plugins\64bit"
$migrated = @()
foreach ($path in Get-OpenStreamLegacyPaths $obsRoot) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force; $migrated += $path }
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -LiteralPath $sourceDll -Destination (Join-Path $destination "openstream-beta-obs.dll") -Force
# OBS data belongs below its data root; do not touch scene collections or settings.
$dataDestination = Join-Path $obsRoot "data\obs-plugins\openstream-beta-obs"
if (Test-Path -LiteralPath $dataDestination) { Remove-Item -LiteralPath $dataDestination -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dataDestination | Out-Null
Copy-Item -Path (Join-Path $sourceData "*") -Destination $dataDestination -Recurse -Force
Write-Host "OpenStream installed: $(Join-Path $destination 'openstream-beta-obs.dll')"
if ($migrated) { Write-Host "Migrated legacy copies:"; $migrated | ForEach-Object { Write-Host "  $_" } }
Write-Host "Restart OBS Studio, then add an OpenStream Beta Camera source."
