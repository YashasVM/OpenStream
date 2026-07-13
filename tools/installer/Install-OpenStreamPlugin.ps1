[CmdletBinding()]
param(
    [string]$ObsInstallDir = $env:OPENSTREAM_OBS_INSTALL
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
    if (-not $env:APPDATA) {
        throw "APPDATA is not available; a per-user OBS plugin location cannot be determined."
    }
    return Join-Path $env:APPDATA "obs-studio\plugins\openstream-obs\bin\64bit\openstream-obs.dll"
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

$targetDll = Get-OpenStreamPluginTarget
$targetDir = Split-Path -Parent $targetDll
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
Copy-Item -LiteralPath $pluginDll -Destination $targetDll -Force

Write-Host ""
Write-Host "OpenStream OBS plugin installed successfully."
Write-Host "Plugin: $targetDll"
Write-Host ""
Write-Host "Restart OBS Studio, then add a source named OpenStream."
