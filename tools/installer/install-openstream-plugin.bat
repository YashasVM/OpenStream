@echo off
setlocal

set "SCRIPT=%~dp0Install-OpenStreamPlugin.ps1"

if not exist "%SCRIPT%" (
    echo ERROR: Install-OpenStreamPlugin.ps1 was not found next to this file.
    echo Extract the full OpenStream plugin zip, then run this installer again.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','%SCRIPT%'); exit $p.ExitCode"
if errorlevel 1 (
    echo ERROR: OpenStream plugin installation failed or was cancelled.
    exit /b 1
)

endlocal
