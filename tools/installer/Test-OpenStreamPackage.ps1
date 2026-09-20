[CmdletBinding()]
param([Parameter(Mandatory)][string]$ZipPath)

& "$PSScriptRoot\Test-OpenStreamInstaller.ps1" -ZipPath $ZipPath
