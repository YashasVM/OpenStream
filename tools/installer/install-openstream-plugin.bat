@echo off
setlocal

rem Elevation: writing under Program Files (the default OBS install) requires an
rem elevated shell. Right-click and choose "Run as administrator", or use the
rem Inno Setup exe which requests elevation automatically (same note as
rem Install-OpenStreamPlugin.ps1).

set "SCRIPT=%~dp0Install-OpenStreamPlugin.ps1"

if not exist "%SCRIPT%" (
    echo ERROR: Install-OpenStreamPlugin.ps1 was not found next to this file.
    echo Extract the full OpenStream plugin zip, then run this installer again.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
if errorlevel 1 (
    echo ERROR: OpenStream plugin installation failed.
    exit /b 1
)

endlocal
