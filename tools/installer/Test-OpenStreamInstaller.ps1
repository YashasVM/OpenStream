[CmdletBinding(DefaultParameterSetName = 'Zip')]
param(
    [Parameter(ParameterSetName = 'Zip', Mandatory)][string]$ZipPath,
    [Parameter(ParameterSetName = 'Inno', Mandatory)][string]$InstallerPath
)

$ErrorActionPreference = 'Stop'

function New-OpenStreamFakeObs {
    param([Parameter(Mandatory)][string]$Root)
    $obs = Join-Path $Root 'obs-studio'
    New-Item -ItemType Directory -Force -Path (Join-Path $obs 'bin\64bit') | Out-Null
    New-Item -ItemType File -Force -Path (Join-Path $obs 'bin\64bit\obs64.exe') | Out-Null
    return $obs
}

function Assert-OpenStreamMigration {
    param([Parameter(Mandatory)][string]$ObsRoot)
    $canonical = Join-Path $ObsRoot 'obs-plugins\64bit\openstream-obs.dll'
    if (!(Test-Path $canonical)) { throw 'Canonical DLL is missing.' }
    if (Test-Path (Join-Path $ObsRoot 'obs-plugins\64bit\openstream-beta-obs.dll')) { throw 'Beta DLL survived migration.' }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ("openstream-installer-" + [guid]::NewGuid())
try {
    $obs = New-OpenStreamFakeObs -Root $root
    if ($PSCmdlet.ParameterSetName -eq 'Zip') {
        $stage = Join-Path $root 'package'
        Expand-Archive -LiteralPath $ZipPath -DestinationPath $stage
        New-Item -ItemType Directory -Force -Path (Join-Path $obs 'obs-plugins\64bit') | Out-Null
        $programData = Join-Path $root 'ProgramData'; $appData = Join-Path $root 'AppData'
        foreach ($name in 'openstream-obs.dll', 'openstream-beta-obs.dll') {
            foreach ($path in @(
                (Join-Path $obs "obs-plugins\64bit\$name"),
                (Join-Path $programData "obs-studio\plugins\openstream-beta-obs\bin\64bit\$name"),
                (Join-Path $appData "obs-studio\plugins\openstream-beta-obs\bin\64bit\$name"))) {
                New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
                Set-Content -LiteralPath $path -Value legacy
            }
        }
        & (Join-Path $stage 'Install-OpenStreamPlugin.ps1') -ObsInstallDir $obs -ProgramDataRoot $programData -AppDataRoot $appData
        Assert-OpenStreamMigration -ObsRoot $obs
        $remaining = Get-ChildItem -Path $obs, $programData, $appData -Filter 'openstream-beta-obs.dll' -Recurse -ErrorAction SilentlyContinue
        if ($remaining) { throw "Legacy plugin survived migration: $($remaining.FullName -join ', ')" }
        Write-Host 'ZIP migration smoke test passed.'
    } else {
        New-Item -ItemType Directory -Force -Path (Join-Path $obs 'obs-plugins\64bit') | Out-Null
        Set-Content -LiteralPath (Join-Path $obs 'obs-plugins\64bit\openstream-beta-obs.dll') -Value legacy
        $process = Start-Process -FilePath (Resolve-Path $InstallerPath) -ArgumentList @(
            '/VERYSILENT',
            '/SUPPRESSMSGBOXES',
            '/CURRENTUSER',
            "/DIR=$obs",
            "/LOG=$(Join-Path $root 'install.log')"
        ) -Wait -PassThru
        if ($process.ExitCode -ne 0) { throw "Installer exited with $($process.ExitCode)." }
        Assert-OpenStreamMigration -ObsRoot $obs
        Write-Host 'Inno installer migration smoke test passed.'
    }
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
