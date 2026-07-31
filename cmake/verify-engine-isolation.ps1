[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
$engineRoot = Join-Path ([IO.Path]::GetFullPath($RepositoryRoot)) 'engine'
if (!(Test-Path -LiteralPath $engineRoot)) {
    Write-Output 'Engine directory is not present yet; isolation check passed.'
    exit 0
}

$patterns = '#\s*include\s*[<"][^>"]*obs|obs-module\.h|find_package\s*\(\s*(OBS|libobs)|target_link_libraries\s*\([^\)]*(OBS|libobs)'
$matches = Get-ChildItem -LiteralPath $engineRoot -Recurse -File |
    Where-Object Extension -In '.c', '.cc', '.cpp', '.cxx', '.h', '.hpp', '.cmake', '.txt' |
    Select-String -Pattern $patterns -CaseSensitive:$false

if ($matches) {
    $locations = $matches | ForEach-Object { "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
    throw "OBS dependency detected in engine:`n$($locations -join "`n")"
}

Write-Output 'No OBS dependency detected in engine.'
