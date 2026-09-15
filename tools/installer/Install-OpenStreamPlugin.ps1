[CmdletBinding()]
param(
    [string]$ObsInstallDir = $env:OPENSTREAM_OBS_INSTALL,
    [string]$ProgramDataRoot = $env:ProgramData,
    [string]$AppDataRoot = $env:APPDATA
)

$ErrorActionPreference = "Stop"

function Test-ObsInstallDir {
    param([Parameter(Mandatory)][string]$Path)

    return (
        (Test-Path -LiteralPath (Join-Path $Path "bin\64bit\obs64.exe")) -or
        (Test-Path -LiteralPath (Join-Path $Path "bin\64bit\obs.exe"))
    )
}

function Find-ObsInstallDir {
    $candidates = @()

    if ($ObsInstallDir) {
        $candidates += $ObsInstallDir
    }

    $registryRoots = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )

    foreach ($root in $registryRoots) {
        $apps = Get-ItemProperty -Path $root -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like "OBS Studio*" -and $_.InstallLocation }
        foreach ($app in $apps) {
            $candidates += $app.InstallLocation
        }
    }

    $candidates += @(
        "$env:ProgramFiles\obs-studio",
        "${env:ProgramFiles(x86)}\obs-studio"
    )

    foreach ($candidate in $candidates | Where-Object { $_ } | Select-Object -Unique) {
        if (Test-ObsInstallDir -Path $candidate) {
            return $candidate
        }
    }

    return $null
}

function Get-OpenStreamPluginTarget {
    param([Parameter(Mandatory)][string]$ObsRoot)
    return Join-Path $ObsRoot "obs-plugins\64bit\openstream-obs.dll"
}

function Get-OpenStreamPluginCopies {
    param([Parameter(Mandatory)][string]$ObsRoot)
    # Stale-path set aligned with tools/installer/openstream-obs-plugin.iss
    # [InstallDelete]: canonical per-user/per-machine plugin dirs for both
    # openstream-obs.dll and openstream-beta-obs.dll, plus the legacy
    # top-level per-user copy (obs-studio\plugins\openstream-obs.dll).
    $roots = @(
        (Join-Path $ObsRoot "obs-plugins\64bit"),
        (Join-Path $ProgramDataRoot "obs-studio\plugins\openstream-beta-obs\bin\64bit"),
        (Join-Path $ProgramDataRoot "obs-studio\plugins\openstream-obs\bin\64bit"),
        (Join-Path $AppDataRoot "obs-studio\plugins\openstream-beta-obs\bin\64bit"),
        (Join-Path $AppDataRoot "obs-studio\plugins\openstream-obs\bin\64bit")
    ) | Where-Object { $_ }
    foreach ($root in $roots) {
        foreach ($name in "openstream-beta-obs.dll", "openstream-obs.dll") {
            Join-Path $root $name
        }
    }
    # Legacy top-level per-user copies (see .iss InstallDelete).
    foreach ($name in "openstream-obs.dll", "openstream-beta-obs.dll") {
        if ($AppDataRoot) { Join-Path $AppDataRoot "obs-studio\plugins\$name" }
        if ($ProgramDataRoot) { Join-Path $ProgramDataRoot "obs-studio\plugins\$name" }
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pluginDll = Join-Path $scriptDir "openstream-obs.dll"

if (-not (Test-Path -LiteralPath $pluginDll)) {
    throw "Could not find openstream-obs.dll next to this installer script. Extract the full plugin zip before running it."
}

$obsRoot = Find-ObsInstallDir
if (-not $obsRoot) {
    throw "OBS Studio was not found. Install OBS Studio or pass -ObsInstallDir 'C:\Program Files\obs-studio'."
}

$runningObs = Get-Process -Name "obs64", "obs" -ErrorAction SilentlyContinue
if ($runningObs) {
    throw "OBS Studio is running. Close OBS before installing or updating OpenStream."
}

$targetDll = Get-OpenStreamPluginTarget -ObsRoot $obsRoot
$targetDir = Split-Path -Parent $targetDll
# Elevation: writing under Program Files (the default OBS install) requires an
# elevated shell. Right-click PowerShell and choose "Run as administrator", or
# use the Inno Setup exe which requests elevation automatically (same note as
# install-openstream-plugin.bat).
# Atomic install: stage the new DLL to a temp file in the target dir, verify it,
# then move it over the canonical copy. Stale copies are removed only after the
# new DLL is verified, so a failed copy never leaves OBS without a plugin.
$stageDll = Join-Path $targetDir ("openstream-obs.dll.new-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
Copy-Item -LiteralPath $pluginDll -Destination $stageDll -Force
$staged = Get-Item -LiteralPath $stageDll
if ($staged.Length -le 0) {
    Remove-Item -LiteralPath $stageDll -Force -ErrorAction SilentlyContinue
    throw "Staged plugin DLL failed verification (empty file): $stageDll"
}
Move-Item -LiteralPath $stageDll -Destination $targetDll -Force
foreach ($copy in Get-OpenStreamPluginCopies -ObsRoot $obsRoot) {
    if ($copy -ne $targetDll -and (Test-Path -LiteralPath $copy)) {
        Remove-Item -LiteralPath $copy -Force
    }
}
$staleData = Join-Path $obsRoot "data\obs-plugins\openstream-beta-obs"
if (Test-Path -LiteralPath $staleData) {
    Remove-Item -LiteralPath $staleData -Recurse -Force
}

Write-Host ""
Write-Host "OpenStream OBS plugin installed successfully."
Write-Host "Plugin: $targetDll"
Write-Host ""
Write-Host "Restart OBS Studio, then add a source named OpenStream V8."
