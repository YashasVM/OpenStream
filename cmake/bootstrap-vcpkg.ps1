[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\out\vcpkg')
)

$ErrorActionPreference = 'Stop'
$commit = 'cd61e1e26a038e82d6550a3ebbe0fbbfe7da78e3'
$destinationPath = [IO.Path]::GetFullPath($Destination)

if (!(Test-Path -LiteralPath $destinationPath)) {
    git clone --no-checkout https://github.com/microsoft/vcpkg.git $destinationPath | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'vcpkg clone failed' }
    git -C $destinationPath checkout --detach $commit | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'vcpkg checkout failed' }
}

if (!(Test-Path -LiteralPath (Join-Path $destinationPath '.git'))) {
    throw "vcpkg destination is not a complete Git checkout: $destinationPath"
}

$origin = (git -C $destinationPath remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $origin -ne 'https://github.com/microsoft/vcpkg.git') {
    throw "vcpkg origin is '$origin'; expected the official HTTPS repository"
}

if (git -C $destinationPath config --get remote.origin.partialclonefilter) {
    throw "vcpkg at $destinationPath is a partial clone. Use a new empty destination."
}

if ((git -C $destinationPath rev-parse --is-shallow-repository).Trim() -eq 'true') {
    git -C $destinationPath fetch --unshallow origin | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'vcpkg history fetch failed' }
}

$actual = (git -C $destinationPath rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actual -ne $commit) {
    throw "vcpkg at $destinationPath is $actual; expected $commit. Use a new empty destination."
}

git -C $destinationPath diff --quiet --no-ext-diff HEAD --
if ($LASTEXITCODE -eq 1) { throw "vcpkg checkout has tracked modifications: $destinationPath" }
if ($LASTEXITCODE -ne 0) { throw 'vcpkg clean-tree check failed' }

& (Join-Path $destinationPath 'bootstrap-vcpkg.bat') -disableMetrics | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'vcpkg bootstrap failed' }

Write-Output $destinationPath
