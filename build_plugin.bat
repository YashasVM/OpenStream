@echo off
setlocal enabledelayedexpansion

:: ─────────────────────────────────────────────────────────────
:: OpenStream V5 OBS Plugin Build Script for Windows
:: ─────────────────────────────────────────────────────────────
:: Prerequisites: VS2022 BuildTools must be installed
:: This script auto-downloads the OBS SDK and FFmpeg headers,
:: builds the plugin DLL, and installs it to your OBS directory.
:: ─────────────────────────────────────────────────────────────

echo.
echo =====================================================
echo   OpenStream V5 - OBS Plugin Builder
echo =====================================================
echo.

:: --- Configuration ---
set "OBS_INSTALL=C:\Program Files\obs-studio"
set "SCRIPT_DIR=%~dp0"
set "PLUGIN_DIR=%SCRIPT_DIR%obs-plugin"
set "BUILD_DIR=%PLUGIN_DIR%\build"
set "DEPS_DIR=%PLUGIN_DIR%\deps"
set "OBS_SDK_DIR=%DEPS_DIR%\obs-sdk"
set "FFMPEG_DIR=%DEPS_DIR%\ffmpeg"

:: OBS SDK download URL (OBS 31.x pre-built SDK)
set "OBS_SDK_URL=https://github.com/obsproject/obs-studio/releases/download/31.0.0/OBS-Studio-31.0.0-SDK-Windows-x64.zip"
set "OBS_SDK_ZIP=%DEPS_DIR%\obs-sdk.zip"

:: FFmpeg headers + libs (from OBS's bundled FFmpeg)
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "CMAKE=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"

:: --- Step 0: Check prerequisites ---
if not exist "%VCVARS%" (
    echo ERROR: VS2022 BuildTools not found at expected path.
    echo Please install Visual Studio 2022 Build Tools with C++ workload.
    exit /b 1
)
if not exist "%CMAKE%" (
    echo ERROR: CMake not found in VS2022 BuildTools.
    exit /b 1
)
if not exist "%OBS_INSTALL%\bin\64bit\obs64.exe" (
    if not exist "%OBS_INSTALL%\bin\64bit\obs.exe" (
        echo ERROR: OBS Studio not found at %OBS_INSTALL%
        echo Please install OBS Studio first.
        exit /b 1
    )
)

echo [1/6] Setting up Visual Studio environment...
call "%VCVARS%" >nul 2>&1

:: --- Step 1: Create deps directory ---
if not exist "%DEPS_DIR%" mkdir "%DEPS_DIR%"

:: --- Step 2: Download OBS SDK if needed ---
if not exist "%OBS_SDK_DIR%\include\obs-module.h" (
    echo [2/6] Downloading OBS SDK...
    
    :: Try to download using curl
    if not exist "%OBS_SDK_ZIP%" (
        curl -L -o "%OBS_SDK_ZIP%" "%OBS_SDK_URL%"
        if errorlevel 1 (
            echo ERROR: Failed to download OBS SDK.
            echo Please manually download from: %OBS_SDK_URL%
            echo and extract to: %OBS_SDK_DIR%
            exit /b 1
        )
    )
    
    echo [2/6] Extracting OBS SDK...
    if not exist "%OBS_SDK_DIR%" mkdir "%OBS_SDK_DIR%"
    powershell -Command "Expand-Archive -Path '%OBS_SDK_ZIP%' -DestinationPath '%OBS_SDK_DIR%' -Force"
    
    :: The SDK may extract into a subdirectory, handle that
    for /d %%D in ("%OBS_SDK_DIR%\*") do (
        if exist "%%D\include\obs-module.h" (
            echo Found SDK in subdirectory: %%D
            xcopy /E /Y /I "%%D\*" "%OBS_SDK_DIR%\" >nul
        )
    )
) else (
    echo [2/6] OBS SDK already present, skipping download.
)

:: --- Step 3: Set up FFmpeg from OBS install ---
echo [3/6] Setting up FFmpeg...
if not exist "%FFMPEG_DIR%" mkdir "%FFMPEG_DIR%"
if not exist "%FFMPEG_DIR%\include" mkdir "%FFMPEG_DIR%\include"
if not exist "%FFMPEG_DIR%\lib" mkdir "%FFMPEG_DIR%\lib"

:: Copy FFmpeg headers from OBS SDK if available
if exist "%OBS_SDK_DIR%\include\libavcodec" (
    xcopy /E /Y /I "%OBS_SDK_DIR%\include\libav*" "%FFMPEG_DIR%\include\" >nul 2>&1
    xcopy /E /Y /I "%OBS_SDK_DIR%\include\libsw*" "%FFMPEG_DIR%\include\" >nul 2>&1
)

:: Create import libraries from OBS's FFmpeg DLLs
echo [3/6] Creating import libraries from OBS FFmpeg DLLs...
set "OBS_BIN=%OBS_INSTALL%\bin\64bit"

:: Generate .lib files from .dll using dumpbin + lib
for %%F in (avcodec avformat avutil swscale) do (
    if not exist "%FFMPEG_DIR%\lib\%%F.lib" (
        if exist "%OBS_BIN%\%%F-*.dll" (
            for %%D in ("%OBS_BIN%\%%F-*.dll") do (
                echo   Creating %%F.lib from %%~nxD...
                dumpbin /exports "%%D" > "%DEPS_DIR%\%%F_exports.txt" 2>nul
                
                :: Create .def file
                echo LIBRARY %%~nxD> "%DEPS_DIR%\%%F.def"
                echo EXPORTS>> "%DEPS_DIR%\%%F.def"
                for /f "skip=19 tokens=4" %%E in (%DEPS_DIR%\%%F_exports.txt) do (
                    if not "%%E"=="" (
                        echo %%E>> "%DEPS_DIR%\%%F.def"
                    )
                )
                lib /def:"%DEPS_DIR%\%%F.def" /out:"%FFMPEG_DIR%\lib\%%F.lib" /machine:x64 >nul 2>&1
            )
        )
    )
)

:: Create obs.lib import library
if not exist "%FFMPEG_DIR%\lib\obs.lib" (
    if exist "%OBS_SDK_DIR%\lib\x64\obs.lib" (
        copy /Y "%OBS_SDK_DIR%\lib\x64\obs.lib" "%FFMPEG_DIR%\lib\obs.lib" >nul
    ) else if exist "%OBS_SDK_DIR%\lib\obs.lib" (
        copy /Y "%OBS_SDK_DIR%\lib\obs.lib" "%FFMPEG_DIR%\lib\obs.lib" >nul
    ) else (
        :: Generate from DLL
        for %%D in ("%OBS_BIN%\obs.dll") do (
            if exist "%%D" (
                echo   Creating obs.lib from obs.dll...
                dumpbin /exports "%%D" > "%DEPS_DIR%\obs_exports.txt" 2>nul
                echo LIBRARY obs.dll> "%DEPS_DIR%\obs.def"
                echo EXPORTS>> "%DEPS_DIR%\obs.def"
                for /f "skip=19 tokens=4" %%E in (%DEPS_DIR%\obs_exports.txt) do (
                    if not "%%E"=="" (
                        echo %%E>> "%DEPS_DIR%\obs.def"
                    )
                )
                lib /def:"%DEPS_DIR%\obs.def" /out:"%FFMPEG_DIR%\lib\obs.lib" /machine:x64 >nul 2>&1
            )
        )
    )
)

:: --- Step 4: Configure with CMake ---
echo [4/6] Configuring CMake build...
if exist "%BUILD_DIR%" rmdir /S /Q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

:: Figure out OBS_ROOT and FFMPEG_ROOT
set "OBS_ROOT=%OBS_SDK_DIR%"
set "FFMPEG_ROOT=%FFMPEG_DIR%"

:: If SDK doesn't have headers, use OBS install as fallback
if not exist "%OBS_SDK_DIR%\include\obs-module.h" (
    set "OBS_ROOT=%OBS_INSTALL%"
)

"%CMAKE%" -S "%PLUGIN_DIR%" -B "%BUILD_DIR%" ^
    -G "NMake Makefiles" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DOBS_ROOT="%OBS_ROOT%" ^
    -DFFMPEG_ROOT="%FFMPEG_ROOT%"

if errorlevel 1 (
    echo.
    echo ERROR: CMake configuration failed.
    echo.
    echo This usually means the OBS SDK headers or FFmpeg libraries
    echo could not be found. You may need to manually specify paths.
    echo.
    echo See the README or docs/setup.md for manual build instructions.
    exit /b 1
)

:: --- Step 5: Build ---
echo [5/6] Building OpenStream V5 plugin...
"%CMAKE%" --build "%BUILD_DIR%" --config Release

if errorlevel 1 (
    echo ERROR: Build failed.
    exit /b 1
)

:: --- Step 6: Install ---
echo [6/6] Installing plugin to OBS...
set "DEST=%OBS_INSTALL%\obs-plugins\64bit"

if exist "%BUILD_DIR%\openstream-obs.dll" (
    copy /Y "%BUILD_DIR%\openstream-obs.dll" "%DEST%\openstream-obs.dll"
    echo.
    echo =====================================================
    echo   SUCCESS! OpenStream V5 plugin installed.
    echo =====================================================
    echo.
    echo   Plugin: %DEST%\openstream-obs.dll
    echo.
    echo   Restart OBS Studio, then:
    echo   1. Add source ^> "OpenStream Phone V5"
    echo   2. Open the Android app on your phone
    echo   3. Video + Audio will stream automatically
    echo   4. Use "Camera Remote Controls" in source
    echo      properties to control zoom/torch/lens
    echo.
) else (
    echo ERROR: Build output not found!
    echo Expected: %BUILD_DIR%\openstream-obs.dll
    exit /b 1
)

endlocal
