@echo off
setlocal enabledelayedexpansion

rem OpenStream OBS Plugin Build Script for Windows.
rem Builds the OBS source plugin, optionally packages it, and installs it by default.

echo.
echo =====================================================
echo   OpenStream - OBS Plugin Builder
echo =====================================================
echo.

set "SCRIPT_DIR=%~dp0"
set "PLUGIN_DIR=%SCRIPT_DIR%obs-plugin"
set "BUILD_DIR=%PLUGIN_DIR%\build"
set "DEPS_DIR=%PLUGIN_DIR%\deps"
set "OBS_SDK_DIR=%DEPS_DIR%\obs-sdk"
set "FFMPEG_DIR=%DEPS_DIR%\ffmpeg"
set "OBS_INSTALL=C:\Program Files\obs-studio"
set "OBS_SDK_URL=https://github.com/obsproject/obs-studio/releases/download/31.0.0/OBS-Studio-31.0.0-SDK-Windows-x64.zip"
set "OBS_SDK_ZIP=%DEPS_DIR%\obs-sdk.zip"
set "OBS_BIN="
set "PACKAGE_DIR="
set "CMAKE_EXE=cmake"

if defined OPENSTREAM_OBS_INSTALL set "OBS_INSTALL=%OPENSTREAM_OBS_INSTALL%"
if defined OPENSTREAM_PLUGIN_BUILD_DIR set "BUILD_DIR=%OPENSTREAM_PLUGIN_BUILD_DIR%"
if defined OPENSTREAM_PLUGIN_PACKAGE_DIR set "PACKAGE_DIR=%OPENSTREAM_PLUGIN_PACKAGE_DIR%"

set "VCVARS=%OPENSTREAM_VCVARS%"
if not defined VCVARS set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
    if exist "%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" (
        for /f "usebackq delims=" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
            set "VS_INSTALL=%%I"
        )
        if defined VS_INSTALL set "VCVARS=!VS_INSTALL!\VC\Auxiliary\Build\vcvars64.bat"
    )
)

if not exist "%VCVARS%" (
    echo ERROR: Visual Studio 2022 C++ build tools were not found.
    echo Install Visual Studio 2022 with the Desktop development with C++ workload.
    exit /b 1
)

where cmake >nul 2>nul
if errorlevel 1 (
    set "CMAKE_EXE=C:\Program Files\Microsoft Visual Studio\2022\Enterprise\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    if not exist "!CMAKE_EXE!" set "CMAKE_EXE=C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    if not exist "!CMAKE_EXE!" set "CMAKE_EXE=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    if not exist "!CMAKE_EXE!" (
        echo ERROR: CMake was not found on PATH or in Visual Studio.
        exit /b 1
    )
)

if exist "%OBS_INSTALL%\bin\64bit\obs64.exe" set "OBS_BIN=%OBS_INSTALL%\bin\64bit"
if not defined OBS_BIN if exist "%OBS_INSTALL%\bin\64bit\obs.exe" set "OBS_BIN=%OBS_INSTALL%\bin\64bit"
if not defined OBS_BIN (
    echo ERROR: OBS Studio was not found at %OBS_INSTALL%.
    echo Set OPENSTREAM_OBS_INSTALL to the OBS install directory if it is elsewhere.
    exit /b 1
)

echo [1/6] Setting up Visual Studio environment...
call "%VCVARS%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Failed to initialize the Visual Studio compiler environment.
    exit /b 1
)

if not exist "%DEPS_DIR%" mkdir "%DEPS_DIR%"

if not exist "%OBS_SDK_DIR%\include\obs-module.h" (
    echo [2/6] Downloading OBS SDK...
    if not exist "%OBS_SDK_ZIP%" (
        curl -L --retry 3 -o "%OBS_SDK_ZIP%" "%OBS_SDK_URL%"
        if errorlevel 1 (
            echo ERROR: Failed to download OBS SDK.
            echo Download manually from: %OBS_SDK_URL%
            echo Then extract it to: %OBS_SDK_DIR%
            exit /b 1
        )
    )

    echo [2/6] Extracting OBS SDK...
    if not exist "%OBS_SDK_DIR%" mkdir "%OBS_SDK_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%OBS_SDK_ZIP%' -DestinationPath '%OBS_SDK_DIR%' -Force"
    if errorlevel 1 exit /b 1

    for /d %%D in ("%OBS_SDK_DIR%\*") do (
        if exist "%%D\include\obs-module.h" (
            xcopy /E /Y /I "%%D\*" "%OBS_SDK_DIR%\" >nul
        )
    )
) else (
    echo [2/6] OBS SDK already present, skipping download.
)

echo [3/6] Setting up FFmpeg import libraries...
if not exist "%FFMPEG_DIR%" mkdir "%FFMPEG_DIR%"
if not exist "%FFMPEG_DIR%\include" mkdir "%FFMPEG_DIR%\include"
if not exist "%FFMPEG_DIR%\lib" mkdir "%FFMPEG_DIR%\lib"

if exist "%OBS_SDK_DIR%\include\libavcodec" (
    xcopy /E /Y /I "%OBS_SDK_DIR%\include\libav*" "%FFMPEG_DIR%\include\" >nul 2>&1
    xcopy /E /Y /I "%OBS_SDK_DIR%\include\libsw*" "%FFMPEG_DIR%\include\" >nul 2>&1
)

for %%F in (avcodec avformat avutil swscale) do (
    if not exist "%FFMPEG_DIR%\lib\%%F.lib" (
        for %%D in ("%OBS_BIN%\%%F-*.dll") do (
            if exist "%%D" (
                echo   Creating %%F.lib from %%~nxD...
                dumpbin /exports "%%D" > "%DEPS_DIR%\%%F_exports.txt" 2>nul
                echo LIBRARY %%~nxD> "%DEPS_DIR%\%%F.def"
                echo EXPORTS>> "%DEPS_DIR%\%%F.def"
                for /f "skip=19 tokens=4" %%E in (%DEPS_DIR%\%%F_exports.txt) do (
                    if not "%%E"=="" echo %%E>> "%DEPS_DIR%\%%F.def"
                )
                lib /def:"%DEPS_DIR%\%%F.def" /out:"%FFMPEG_DIR%\lib\%%F.lib" /machine:x64 >nul 2>&1
            )
        )
    )
)

if not exist "%FFMPEG_DIR%\lib\obs.lib" (
    if exist "%OBS_SDK_DIR%\lib\x64\obs.lib" (
        copy /Y "%OBS_SDK_DIR%\lib\x64\obs.lib" "%FFMPEG_DIR%\lib\obs.lib" >nul
    ) else if exist "%OBS_SDK_DIR%\lib\obs.lib" (
        copy /Y "%OBS_SDK_DIR%\lib\obs.lib" "%FFMPEG_DIR%\lib\obs.lib" >nul
    ) else if exist "%OBS_BIN%\obs.dll" (
        echo   Creating obs.lib from obs.dll...
        dumpbin /exports "%OBS_BIN%\obs.dll" > "%DEPS_DIR%\obs_exports.txt" 2>nul
        echo LIBRARY obs.dll> "%DEPS_DIR%\obs.def"
        echo EXPORTS>> "%DEPS_DIR%\obs.def"
        for /f "skip=19 tokens=4" %%E in (%DEPS_DIR%\obs_exports.txt) do (
            if not "%%E"=="" echo %%E>> "%DEPS_DIR%\obs.def"
        )
        lib /def:"%DEPS_DIR%\obs.def" /out:"%FFMPEG_DIR%\lib\obs.lib" /machine:x64 >nul 2>&1
    )
)

echo [4/6] Configuring CMake build...
if exist "%BUILD_DIR%" rmdir /S /Q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

set "OBS_ROOT=%OBS_SDK_DIR%"
if not exist "%OBS_SDK_DIR%\include\obs-module.h" set "OBS_ROOT=%OBS_INSTALL%"

"%CMAKE_EXE%" -S "%PLUGIN_DIR%" -B "%BUILD_DIR%" ^
    -G "NMake Makefiles" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DOBS_ROOT="%OBS_ROOT%" ^
    -DFFMPEG_ROOT="%FFMPEG_DIR%"

if errorlevel 1 (
    echo ERROR: CMake configuration failed.
    exit /b 1
)

echo [5/6] Building OpenStream plugin...
"%CMAKE_EXE%" --build "%BUILD_DIR%" --config Release
if errorlevel 1 (
    echo ERROR: Build failed.
    exit /b 1
)

if not exist "%BUILD_DIR%\openstream-obs.dll" (
    echo ERROR: Build output not found: %BUILD_DIR%\openstream-obs.dll
    exit /b 1
)

if defined PACKAGE_DIR (
    echo [6/6] Packaging plugin artifact...
    if not exist "%PACKAGE_DIR%" mkdir "%PACKAGE_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '%BUILD_DIR%\openstream-obs.dll' -DestinationPath '%PACKAGE_DIR%\openstream-obs-windows-x64.zip' -Force"
    if errorlevel 1 exit /b 1
) else (
    echo [6/6] Packaging skipped.
)

if /I "%OPENSTREAM_SKIP_INSTALL%"=="1" (
    echo Install skipped because OPENSTREAM_SKIP_INSTALL=1.
    echo Built plugin: %BUILD_DIR%\openstream-obs.dll
    endlocal
    exit /b 0
)

echo Installing plugin to OBS...
set "DEST=%OBS_INSTALL%\obs-plugins\64bit"
copy /Y "%BUILD_DIR%\openstream-obs.dll" "%DEST%\openstream-obs.dll"
if errorlevel 1 (
    echo ERROR: Failed to copy plugin to %DEST%.
    exit /b 1
)

echo.
echo =====================================================
echo   SUCCESS! OpenStream plugin installed.
echo =====================================================
echo.
echo   Plugin: %DEST%\openstream-obs.dll
echo.
echo Restart OBS Studio, then add an OpenStream source.
echo.

endlocal
