[CmdletBinding()]
param(
    [string]$ObsInstallDir = $env:OPENSTREAM_OBS_INSTALL
)

$ErrorActionPreference = "Stop"

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

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

if (-not (Test-IsAdministrator)) {
    throw "Run this installer from an elevated PowerShell window, or use install-openstream-plugin.bat to request administrator access."
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
    Write-Warning "OBS Studio appears to be running. Close and restart OBS after installation so it can load the plugin."
}

$destinationDir = Join-Path $obsRoot "obs-plugins\64bit"
New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null

$destinationDll = Join-Path $destinationDir "openstream-obs.dll"
Copy-Item -LiteralPath $pluginDll -Destination $destinationDll -Force

Write-Host ""
Write-Host "OpenStream OBS plugin installed successfully."
Write-Host "Plugin: $destinationDll"
Write-Host ""
Write-Host "Restart OBS Studio, then add a source named OpenStream."
