[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$InstalledDirectory,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\out\provenance'),
    [string]$VcpkgRoot = $env:VCPKG_ROOT
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$installedRoot = [IO.Path]::GetFullPath($InstalledDirectory)
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$statusPath = Join-Path $installedRoot 'vcpkg\status'
if (!(Test-Path -LiteralPath $statusPath)) { throw "Missing vcpkg status file: $statusPath" }

$dependencies = @()
$noticeBlocks = @()
$paragraphs = (Get-Content -Raw -LiteralPath $statusPath) -split '(?:\r?\n){2,}'
foreach ($paragraph in $paragraphs) {
    $fields = @{}
    foreach ($line in $paragraph -split '\r?\n') {
        if ($line -match '^([^:]+):\s*(.+)$') { $fields[$matches[1]] = $matches[2] }
    }
    if (!$fields.Package -or !$fields.Version -or !$fields.Architecture) { continue }
    $copyrightPath = Join-Path $installedRoot "$($fields.Architecture)\share\$($fields.Package)\copyright"
    if (!(Test-Path -LiteralPath $copyrightPath)) { throw "Missing license for $($fields.Package): $copyrightPath" }
    $licenseHash = (Get-FileHash -LiteralPath $copyrightPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $dependencies += [ordered]@{
        name = $fields.Package
        version = $fields.Version
        port_version = if ($fields.'Port-Version') { [int]$fields.'Port-Version' } else { 0 }
        architecture = $fields.Architecture
        abi = $fields.Abi
        license_sha256 = $licenseHash
    }
    $noticeBlocks += "===== $($fields.Package) $($fields.Version) [$($fields.Architecture)] =====`n$((Get-Content -Raw -LiteralPath $copyrightPath).Trim())"
}
if (!$dependencies) { throw 'No installed dependencies were found' }

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$manifestPath = Join-Path $repositoryRoot 'vcpkg.json'
$presetPath = Join-Path $repositoryRoot 'CMakePresets.json'
$lockInputs = @(
    [ordered]@{ path = 'vcpkg.json'; full_path = $manifestPath },
    [ordered]@{ path = 'CMakePresets.json'; full_path = $presetPath }
) | ForEach-Object {
    [ordered]@{
        path = $_.path
        sha256 = (Get-FileHash -LiteralPath $_.full_path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$vcpkgExecutable = if ($VcpkgRoot) { Join-Path ([IO.Path]::GetFullPath($VcpkgRoot)) 'vcpkg.exe' }
if (!$vcpkgExecutable -or !(Test-Path -LiteralPath $vcpkgExecutable)) { throw 'Pinned vcpkg.exe was not found' }
$provenance = [ordered]@{
    schema_version = 1
    vcpkg_baseline = $manifest.'builtin-baseline'
    vcpkg_tool_sha256 = (Get-FileHash -LiteralPath $vcpkgExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    inputs = $lockInputs
    dependencies = $dependencies
}
$provenance | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'dependencies.json')
($noticeBlocks -join "`n`n") + "`n" | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'THIRD_PARTY_NOTICES.txt')

Get-ChildItem -LiteralPath $outputRoot -File | ForEach-Object FullName
