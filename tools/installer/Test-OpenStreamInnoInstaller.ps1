[CmdletBinding()]
param([Parameter(Mandatory)][string]$InstallerPath)

& "$PSScriptRoot\Test-OpenStreamInstaller.ps1" -InstallerPath $InstallerPath
