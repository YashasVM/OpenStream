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
set "OPENSTREAM_OBS_VERSION=32.2.1"
set "OBS_SDK_URL=https://github.com/obsproject/obs-studio/releases/download/32.2.1/OBS-Studio-32.2.1-Sources.tar.gz"
set "OBS_SDK_SHA256=6a2532b1094bc51bc2fdeb1068d5c19cfe04216191a5b35c8707625401a80bf4"
set "OBS_SDK_ZIP=%DEPS_DIR%\obs-source.tar.gz"
set "OBS_DEPS_URL=https://github.com/obsproject/obs-deps/releases/download/2025-08-23/windows-deps-2025-08-23-x64.zip"
set "OBS_DEPS_SHA256=8de229cff6f1981508c0eb646b35e644633a5855787b9f5d3b90ae2aeb87ffc1"
set "OBS_DEPS_ZIP=%DEPS_DIR%\obs-deps.zip"
set "OBS_DEPS_DIR=%DEPS_DIR%\obs-deps"
set "OBS_BIN="
set "PACKAGE_DIR="
set "CMAKE_EXE=cmake"
set "QT_ROOT=%OPENSTREAM_QT_ROOT%"
if not defined OPENSTREAM_VERSION set "OPENSTREAM_VERSION=1.0.1"

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

powershell -NoProfile -Command "$v=(Get-Item -LiteralPath '%OBS_BIN%\obs64.exe').VersionInfo.ProductVersion; if(-not $v.StartsWith('%OPENSTREAM_OBS_VERSION%')){ throw 'Expected OBS %OPENSTREAM_OBS_VERSION%, found '+$v }"
if errorlevel 1 exit /b 1

for %%F in (avformat-62.dll avcodec-62.dll avutil-60.dll swscale-9.dll) do (
    if not exist "%OBS_BIN%\%%F" (
        echo ERROR: OBS %OPENSTREAM_OBS_VERSION% x64 with %%F was required, but it was not found.
        exit /b 1
    )
)

if not defined QT_ROOT (
    echo ERROR: Qt 6 development files were not found.
    echo Set OPENSTREAM_QT_ROOT to the Qt directory matching OBS, for example C:\Qt\6.8.3\msvc2022_64.
    exit /b 1
)
if not exist "%QT_ROOT%\lib\cmake\Qt6\Qt6Config.cmake" (
    echo ERROR: OPENSTREAM_QT_ROOT does not contain Qt6Config.cmake: %QT_ROOT%
    exit /b 1
)

echo [1/6] Setting up Visual Studio environment...
call "%VCVARS%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Failed to initialize the Visual Studio compiler environment.
    exit /b 1
)

if not exist "%DEPS_DIR%" mkdir "%DEPS_DIR%"

if not exist "%OBS_SDK_DIR%\libobs\obs-module.h" (
    echo [2/6] Downloading OBS source headers...
    if not exist "%OBS_SDK_ZIP%" (
        curl -L --retry 3 -o "%OBS_SDK_ZIP%" "%OBS_SDK_URL%"
        if errorlevel 1 (
            echo ERROR: Failed to download OBS source headers.
            echo Download manually from: %OBS_SDK_URL%
            echo Then extract it to: %OBS_SDK_DIR%
            exit /b 1
        )
    )

    echo [2/6] Extracting OBS source headers...
    if not exist "%OBS_SDK_DIR%" mkdir "%OBS_SDK_DIR%"
    tar -xzf "%OBS_SDK_ZIP%" -C "%OBS_SDK_DIR%" --strip-components=1
    rem Windows tar cannot materialize a few Unix helper symlinks. They are not
    rem part of the plugin SDK, so validate the headers we actually consume.
    if not exist "%OBS_SDK_DIR%\libobs\obs-module.h" (
        echo ERROR: OBS headers were not extracted successfully.
        exit /b 1
    )
    if not exist "%OBS_SDK_DIR%\frontend\api\obs-frontend-api.h" (
        echo ERROR: OBS frontend API headers were not extracted successfully.
        exit /b 1
    )
) else (
    echo [2/6] OBS source headers already present, skipping download.
)

echo [3/6] Setting up pinned OBS FFmpeg headers and import libraries...
if not exist "%OBS_DEPS_DIR%\include\libavcodec\avcodec.h" (
    if not exist "%OBS_DEPS_ZIP%" curl -L --retry 3 -o "%OBS_DEPS_ZIP%" "%OBS_DEPS_URL%"
    if errorlevel 1 exit /b 1
    for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -LiteralPath '%OBS_DEPS_ZIP%' -Algorithm SHA256).Hash.ToLowerInvariant()"') do set "OBS_DEPS_ACTUAL=%%H"
    if /I not "!OBS_DEPS_ACTUAL!"=="%OBS_DEPS_SHA256%" (
        echo ERROR: OBS dependency checksum mismatch.
        exit /b 1
    )
    for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -LiteralPath '%OBS_SDK_ZIP%' -Algorithm SHA256).Hash.ToLowerInvariant()"') do set "OBS_SDK_ACTUAL=%%H"
    if /I not "!OBS_SDK_ACTUAL!"=="%OBS_SDK_SHA256%" (
        echo ERROR: OBS source checksum mismatch.
        exit /b 1
    )
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%OBS_DEPS_ZIP%' -DestinationPath '%OBS_DEPS_DIR%' -Force"
    if errorlevel 1 exit /b 1
)
if not exist "%FFMPEG_DIR%" mkdir "%FFMPEG_DIR%"
if not exist "%FFMPEG_DIR%\include" mkdir "%FFMPEG_DIR%\include"
if not exist "%FFMPEG_DIR%\lib" mkdir "%FFMPEG_DIR%\lib"

if not exist "%FFMPEG_DIR%\include\libavcodec\avcodec.h" xcopy /E /Y /I "%OBS_DEPS_DIR%\include\*" "%FFMPEG_DIR%\include\" >nul

rem OBS 32.2.1's published dependency preset still contains the previous
rem FFmpeg import names. Generate only the import tables from the exact,
rem version-checked OBS 32.2.1 DLLs above so the linker cannot retain that ABI.
for %%F in (avcodec avformat avutil swscale) do (
    for %%D in ("%OBS_BIN%\%%F-*.dll") do (
        if exist "%%D" (
            echo   Creating %%F.lib from verified %%~nxD...
            dumpbin /exports "%%D" > "%DEPS_DIR%\%%F_exports.txt" 2>nul
            echo LIBRARY %%~nxD> "%DEPS_DIR%\%%F.def"
            echo EXPORTS>> "%DEPS_DIR%\%%F.def"
            for /f "usebackq skip=19 tokens=4" %%E in ("%DEPS_DIR%\%%F_exports.txt") do (
                if not "%%E"=="" echo %%E>> "%DEPS_DIR%\%%F.def"
            )
            lib /def:"%DEPS_DIR%\%%F.def" /out:"%FFMPEG_DIR%\lib\%%F.lib" /machine:x64 >nul 2>&1
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
        for /f "usebackq skip=19 tokens=4" %%E in ("%DEPS_DIR%\obs_exports.txt") do (
            if not "%%E"=="" echo %%E>> "%DEPS_DIR%\obs.def"
        )
        lib /def:"%DEPS_DIR%\obs.def" /out:"%FFMPEG_DIR%\lib\obs.lib" /machine:x64 >nul 2>&1
    )
)

if not exist "%FFMPEG_DIR%\lib\obs-frontend-api.lib" (
    if exist "%OBS_BIN%\obs-frontend-api.dll" (
        echo   Creating obs-frontend-api.lib from obs-frontend-api.dll...
        dumpbin /exports "%OBS_BIN%\obs-frontend-api.dll" > "%DEPS_DIR%\obs_frontend_exports.txt" 2>nul
        echo LIBRARY obs-frontend-api.dll> "%DEPS_DIR%\obs-frontend-api.def"
        echo EXPORTS>> "%DEPS_DIR%\obs-frontend-api.def"
        for /f "usebackq skip=19 tokens=4" %%E in ("%DEPS_DIR%\obs_frontend_exports.txt") do (
            if not "%%E"=="" echo %%E>> "%DEPS_DIR%\obs-frontend-api.def"
        )
        lib /def:"%DEPS_DIR%\obs-frontend-api.def" /out:"%FFMPEG_DIR%\lib\obs-frontend-api.lib" /machine:x64 >nul 2>&1
    )
)
if not exist "%FFMPEG_DIR%\lib\obs-frontend-api.lib" (
    echo ERROR: Could not create the OBS frontend API import library.
    exit /b 1
)

echo [4/6] Configuring CMake build...
if exist "%BUILD_DIR%" rmdir /S /Q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

set "OBS_ROOT=%OBS_SDK_DIR%"
if not exist "%OBS_SDK_DIR%\libobs\obs-module.h" set "OBS_ROOT=%OBS_INSTALL%"

"%CMAKE_EXE%" -S "%PLUGIN_DIR%" -B "%BUILD_DIR%" ^
    -G "NMake Makefiles" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_PREFIX_PATH="%QT_ROOT%" ^
    -DOBS_ROOT="%OBS_ROOT%" ^
    -DOPENSTREAM_OBS_BIN="%OBS_BIN%" ^
    -DFFMPEG_ROOT="%FFMPEG_DIR%" ^
    -DOPENSTREAM_VERSION="%OPENSTREAM_VERSION%"

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
    set "STAGE_DIR=%PACKAGE_DIR%\openstream-obs-windows-x64"
    if exist "!STAGE_DIR!" rmdir /S /Q "!STAGE_DIR!"
    mkdir "!STAGE_DIR!"
    copy /Y "%BUILD_DIR%\openstream-obs.dll" "!STAGE_DIR!\openstream-obs.dll" >nul
    copy /Y "%SCRIPT_DIR%tools\installer\Install-OpenStreamPlugin.ps1" "!STAGE_DIR!\Install-OpenStreamPlugin.ps1" >nul
    copy /Y "%SCRIPT_DIR%tools\installer\install-openstream-plugin.bat" "!STAGE_DIR!\install-openstream-plugin.bat" >nul
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '!STAGE_DIR!\*' -DestinationPath '%PACKAGE_DIR%\openstream-obs-windows-x64.zip' -Force"
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
if not exist "%DEST%" mkdir "%DEST%"
del /Q "%DEST%\openstream-beta-obs.dll" 2>nul
del /Q "%DEST%\openstream-obs.dll" 2>nul
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
