param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = "Stop"

try {
    $versionProperties = Get-Content -LiteralPath $Path
    $declarations = @(
        $versionProperties | Where-Object { $_ -match '^\s*productVersion(?:\s|=|:|$)' }
    )

    if ($declarations.Count -ne 1) {
        throw "Expected exactly one productVersion declaration in $Path."
    }

    $match = [regex]::Match($declarations[0], '^productVersion=([0-9]+\.[0-9]+\.[0-9]+)$')
    if (-not $match.Success) {
        throw "productVersion must use MAJOR.MINOR.PATCH syntax in $Path."
    }

    Write-Output $match.Groups[1].Value
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
