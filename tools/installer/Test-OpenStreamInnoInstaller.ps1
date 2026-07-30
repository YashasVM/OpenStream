[CmdletBinding()]
param([Parameter(Mandatory)][string]$InstallerPath)

$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ("openstream-inno-" + [guid]::NewGuid())
try {
    $obs = Join-Path $root 'obs-studio'
    New-Item -ItemType Directory -Force -Path (Join-Path $obs 'bin\64bit'), (Join-Path $obs 'obs-plugins\64bit') | Out-Null
    New-Item -ItemType File -Path (Join-Path $obs 'bin\64bit\obs64.exe') | Out-Null
    Set-Content -LiteralPath (Join-Path $obs 'obs-plugins\64bit\openstream-beta-obs.dll') -Value legacy

    $process = Start-Process -FilePath (Resolve-Path $InstallerPath) -ArgumentList @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/CURRENTUSER',
        "/DIR=$obs",
        "/LOG=$(Join-Path $root 'install.log')"
    ) -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Installer exited with $($process.ExitCode)." }
    if (Test-Path (Join-Path $obs 'obs-plugins\64bit\openstream-beta-obs.dll')) { throw 'Beta DLL survived migration.' }
    if (!(Test-Path (Join-Path $obs 'obs-plugins\64bit\openstream-obs.dll'))) { throw 'Canonical DLL is missing.' }
    Write-Host 'Inno installer migration smoke test passed.'
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
