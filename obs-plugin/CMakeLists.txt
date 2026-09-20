cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 1.0.1 LANGUAGES CXX)
include(CTest)
find_package(Threads REQUIRED)
# Version-consistency: keep 1.0.1 in sync with android/app/build.gradle.kts
# ("1.0.1"), tools/installer/openstream-obs-plugin.iss (#define OpenStreamVersion),
# build_plugin.bat OPENSTREAM_VERSION, and build_plugin_linux.sh OPENSTREAM_VERSION.
# Releases ship the APK + plugin atomically.
set(OPENSTREAM_VERSION "${PROJECT_VERSION}" CACHE STRING "Version embedded in the OBS plugin")

# Releases are built against OBS Studio 32.2.1 x64.  Its bundled FFmpeg ABI is
# avformat 62, avcodec 62, avutil 60 and swscale 9.  Keeping this declaration
# here makes accidental ABI changes fail at configure time instead of at OBS
# module-load time.  The enforcement below is Windows-only: Linux links the
# distro OBS/FFmpeg system ABI (see the Linux branch and docs/linux.md).
set(OPENSTREAM_OBS_BASELINE "32.2.1")
set(OPENSTREAM_FFMPEG_ABI "avformat-62;avcodec-62;avutil-60;swscale-9")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(openstream-obs MODULE
    src/async-control-client.cpp
    src/openstream-source.cpp
)

if (BUILD_TESTING)
    add_executable(openstream-obs-contract-tests
        tests/test_contracts.cpp
        src/async-control-client.cpp
    )
    target_include_directories(openstream-obs-contract-tests PRIVATE src)
    target_link_libraries(openstream-obs-contract-tests PRIVATE Threads::Threads)
    add_test(NAME openstream-obs-contract-tests COMMAND openstream-obs-contract-tests)

    # POSIX SIGPIPE hardening for the camera-control channel has no libobs/Qt
    # dependency, so it is covered by a dedicated hermetic test (forked
    # children, disconnected peer, default SIGPIPE disposition).
    add_executable(openstream-obs-sigpipe-tests
        tests/test_sigpipe.cpp
    )
    target_include_directories(openstream-obs-sigpipe-tests PRIVATE src)
    add_test(NAME openstream-obs-sigpipe-tests COMMAND openstream-obs-sigpipe-tests)
endif()

find_package(Qt6 6.2 COMPONENTS Core REQUIRED)

if (BUILD_TESTING)
    add_executable(openstream-solo-camera-tests tests/test_solo_camera.cpp)
    target_include_directories(openstream-solo-camera-tests PRIVATE src)
    target_link_libraries(openstream-solo-camera-tests PRIVATE Threads::Threads)
    add_test(NAME openstream-solo-camera-tests COMMAND openstream-solo-camera-tests)

    add_executable(openstream-control-json-tests tests/test_control_json.cpp)
    target_include_directories(openstream-control-json-tests PRIVATE src)
    target_link_libraries(openstream-control-json-tests PRIVATE Qt6::Core)
    add_test(NAME openstream-control-json-tests COMMAND openstream-control-json-tests)
    if (WIN32)
        set_tests_properties(openstream-control-json-tests PROPERTIES
            ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:Qt6::Core>")
    endif()
endif()

if (WIN32)
    if (NOT DEFINED OBS_ROOT)
        message(FATAL_ERROR "Set OBS_ROOT to the OBS Studio install, source checkout, build tree, or development SDK root.")
    endif()

    find_path(OBS_INCLUDE_DIR
        NAMES obs-module.h
        HINTS ${OBS_ROOT}
        PATH_SUFFIXES include libobs
        REQUIRED
    )

    find_path(AVFORMAT_INCLUDE_DIR
        NAMES libavformat/avformat.h
        HINTS ${FFMPEG_ROOT}
        PATH_SUFFIXES include
        REQUIRED
    )
    find_library(AVFORMAT_LIBRARY
        NAMES avformat
        HINTS ${FFMPEG_ROOT}
        PATH_SUFFIXES lib lib/x64
        REQUIRED
    )
    find_library(AVCODEC_LIBRARY
        NAMES avcodec
        HINTS ${FFMPEG_ROOT}
        PATH_SUFFIXES lib lib/x64
        REQUIRED
    )
    find_library(AVUTIL_LIBRARY
        NAMES avutil
        HINTS ${FFMPEG_ROOT}
        PATH_SUFFIXES lib lib/x64
        REQUIRED
    )
    find_library(SWSCALE_LIBRARY
        NAMES swscale
        HINTS ${FFMPEG_ROOT}
        PATH_SUFFIXES lib lib/x64
        REQUIRED
    )

    # Enforce the FFmpeg ABI gate even when OPENSTREAM_OBS_BIN is unset: fail
    # explicitly instead of silently skipping verification and linking an
    # unchecked ABI.
    if (NOT DEFINED OPENSTREAM_OBS_BIN OR "${OPENSTREAM_OBS_BIN}" STREQUAL "")
        message(FATAL_ERROR "OPENSTREAM_OBS_BIN must point to the OBS ${OPENSTREAM_OBS_BASELINE} x64 bin dir for FFmpeg ABI verification.")
    endif()
    foreach(_openstream_ffmpeg_dll IN LISTS OPENSTREAM_FFMPEG_ABI)
        if (NOT EXISTS "${OPENSTREAM_OBS_BIN}/${_openstream_ffmpeg_dll}.dll" AND
            NOT EXISTS "${OPENSTREAM_OBS_BIN}/${_openstream_ffmpeg_dll}.DLL")
            message(FATAL_ERROR "OpenStream releases require OBS ${OPENSTREAM_OBS_BASELINE} x64 and ${_openstream_ffmpeg_dll}.dll")
        endif()
    endforeach()

    find_library(OBS_LIBRARY
        NAMES obs libobs
        HINTS ${OBS_ROOT} ${FFMPEG_ROOT}
        PATH_SUFFIXES lib lib/x64 build/libobs/Release build_x64/libobs/Release bin/64bit bin/64bit/obs
        REQUIRED
    )
else()
    # Linux: build against the distro OBS Studio / FFmpeg / Qt6 development
    # packages (e.g. libobs-dev, libavformat-dev, qt6-base-dev). OBS_ROOT is
    # not required; pkg-config locates the system ABI first and standard
    # include/library paths are the fallback. There is intentionally no
    # FFmpeg ABI pin on this path: the module links whatever
    # libavformat/libavcodec the system OBS was built against (documented in
    # docs/linux.md). The Windows release pin above is untouched.
    find_package(PkgConfig QUIET)
    if (PkgConfig_FOUND)
        pkg_check_modules(PC_LIBOBS QUIET libobs)
        pkg_check_modules(PC_AVFORMAT QUIET libavformat)
        pkg_check_modules(PC_AVCODEC QUIET libavcodec)
        pkg_check_modules(PC_AVUTIL QUIET libavutil)
        pkg_check_modules(PC_SWSCALE QUIET libswscale)
    endif()

    find_path(OBS_INCLUDE_DIR
        NAMES obs-module.h
        HINTS ${PC_LIBOBS_INCLUDE_DIRS}
        PATHS /usr/include/obs /usr/local/include/obs
        REQUIRED
    )
    find_library(OBS_LIBRARY
        NAMES obs
        HINTS ${PC_LIBOBS_LIBRARY_DIRS}
        REQUIRED
    )
    find_path(AVFORMAT_INCLUDE_DIR
        NAMES libavformat/avformat.h
        HINTS ${PC_AVFORMAT_INCLUDE_DIRS}
        PATHS /usr/include /usr/local/include
        REQUIRED
    )
    find_library(AVFORMAT_LIBRARY
        NAMES avformat
        HINTS ${PC_AVFORMAT_LIBRARY_DIRS}
        REQUIRED
    )
    find_library(AVCODEC_LIBRARY
        NAMES avcodec
        HINTS ${PC_AVCODEC_LIBRARY_DIRS}
        REQUIRED
    )
    find_library(AVUTIL_LIBRARY
        NAMES avutil
        HINTS ${PC_AVUTIL_LIBRARY_DIRS}
        REQUIRED
    )
    find_library(SWSCALE_LIBRARY
        NAMES swscale
        HINTS ${PC_SWSCALE_LIBRARY_DIRS}
        REQUIRED
    )
endif()

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/obsconfig.h"
"#pragma once\n"
"#define OBS_DATA_PATH \"data/obs-studio\"\n"
"#define OBS_PLUGIN_PATH \"obs-plugins/64bit\"\n"
"#define OBS_PLUGIN_DESTINATION \"obs-plugins/64bit\"\n"
"#define OBS_RELEASE_CANDIDATE 0\n"
"#define OBS_BETA 0\n"
)

target_include_directories(openstream-obs PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${OBS_INCLUDE_DIR}
    ${AVFORMAT_INCLUDE_DIR}
)

target_link_libraries(openstream-obs PRIVATE
    ${OBS_LIBRARY}
    Qt6::Core
    ${AVFORMAT_LIBRARY}
    ${AVCODEC_LIBRARY}
    ${AVUTIL_LIBRARY}
    ${SWSCALE_LIBRARY}
)

if (WIN32)
    target_link_libraries(openstream-obs PRIVATE ws2_32 iphlpapi)
endif()

if (BUILD_TESTING AND UNIX AND NOT APPLE)
    add_executable(openstream-source-settings-tests
        tests/test_source_settings.cpp src/async-control-client.cpp)
    get_target_property(_plugin_includes openstream-obs INCLUDE_DIRECTORIES)
    get_target_property(_plugin_libraries openstream-obs LINK_LIBRARIES)
    target_include_directories(openstream-source-settings-tests PRIVATE ${_plugin_includes})
    target_link_libraries(openstream-source-settings-tests PRIVATE ${_plugin_libraries} Threads::Threads)
    target_compile_definitions(openstream-source-settings-tests PRIVATE OPENSTREAM_VERSION="${OPENSTREAM_VERSION}")
    add_test(NAME openstream-source-settings-tests COMMAND openstream-source-settings-tests)
endif()

target_compile_definitions(openstream-obs PRIVATE
    OPENSTREAM_VERSION="${OPENSTREAM_VERSION}"
)

if (WIN32)
    # Keep the existing Windows packaging contract outside this Linux release.
    set_target_properties(openstream-obs PROPERTIES PREFIX "")
else()
    # A distinct module name allows shin and OpenStream to coexist.
    set_target_properties(openstream-obs PROPERTIES PREFIX "" OUTPUT_NAME "shin-obs")
endif()

# Install rules: `cmake --install` stages the module into OBS's plugin dir.
# Windows keeps the historical obs-plugins/64bit layout; Linux follows the
# distro libdir (e.g. /usr/lib/obs-plugins or ~/.local/lib/obs-plugins).
if (WIN32)
    install(TARGETS openstream-obs
        RUNTIME DESTINATION "obs-plugins/64bit"
        LIBRARY DESTINATION "obs-plugins/64bit"
        ARCHIVE DESTINATION "obs-plugins/64bit")
else()
    include(GNUInstallDirs)
    install(TARGETS openstream-obs
        LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}/obs-plugins")
endif()

message(STATUS "Building OpenStream OBS source with FFmpeg SRT input support.")
message(STATUS "OpenStream OBS baseline: ${OPENSTREAM_OBS_BASELINE}; FFmpeg ABI: ${OPENSTREAM_FFMPEG_ABI}")
if (NOT WIN32)
    message(STATUS "OpenStream Linux build: linking system OBS/FFmpeg ABI (no Windows version pin). See docs/linux.md.")
endif()
