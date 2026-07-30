cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 2.1.1 LANGUAGES CXX)

# Releases are built against OBS Studio 32.2.1 x64.  Its bundled FFmpeg ABI is
# avformat 62, avcodec 62, avutil 60 and swscale 9.  Keeping this declaration
# here makes accidental ABI changes fail at configure time instead of at OBS
# module-load time.
set(OPENSTREAM_OBS_BASELINE "32.2.1")
set(OPENSTREAM_FFMPEG_ABI "avformat-62;avcodec-62;avutil-60;swscale-9")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED OBS_ROOT)
    message(FATAL_ERROR "Set OBS_ROOT to the OBS Studio install, source checkout, build tree, or development SDK root.")
endif()

set(OPENSTREAM_SOURCES src/openstream-source.cpp)

# OBS ships a Qt 6 frontend. Normal and release builds require its development
# package so a successful build always contains the camera dock. The opt-out is
# intended only for minimal SDK contract checks, never for distributable builds.
option(OPENSTREAM_BUILD_DOCK "Build the required OpenStream Beta Control Room Qt dock" ON)
if (OPENSTREAM_BUILD_DOCK)
    find_package(Qt6 6.2 COMPONENTS Widgets REQUIRED)
    list(APPEND OPENSTREAM_SOURCES src/openstream-dock.cpp)
else()
    list(APPEND OPENSTREAM_SOURCES src/openstream-dock-stub.cpp)
    message(WARNING "OPENSTREAM_BUILD_DOCK=OFF: building a non-release plugin without the camera dock.")
endif()

add_library(openstream-beta-obs MODULE ${OPENSTREAM_SOURCES})

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

if (WIN32 AND DEFINED OPENSTREAM_OBS_BIN)
    foreach(_openstream_ffmpeg_dll IN LISTS OPENSTREAM_FFMPEG_ABI)
        if (NOT EXISTS "${OPENSTREAM_OBS_BIN}/${_openstream_ffmpeg_dll}.dll" AND
            NOT EXISTS "${OPENSTREAM_OBS_BIN}/${_openstream_ffmpeg_dll}.DLL")
            message(FATAL_ERROR "OpenStream releases require OBS ${OPENSTREAM_OBS_BASELINE} x64 and ${_openstream_ffmpeg_dll}.dll")
        endif()
    endforeach()
endif()

find_library(OBS_LIBRARY
    NAMES obs libobs
    HINTS ${OBS_ROOT} ${FFMPEG_ROOT}
    PATH_SUFFIXES lib lib/x64 build/libobs/Release build_x64/libobs/Release bin/64bit bin/64bit/obs
    REQUIRED
)

file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/obsconfig.h"
"#pragma once\n"
"#define OBS_DATA_PATH \"data/obs-studio\"\n"
"#define OBS_PLUGIN_PATH \"obs-plugins/64bit\"\n"
"#define OBS_PLUGIN_DESTINATION \"obs-plugins/64bit\"\n"
"#define OBS_RELEASE_CANDIDATE 0\n"
"#define OBS_BETA 0\n"
)

target_include_directories(openstream-beta-obs PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${OBS_INCLUDE_DIR}
    ${OBS_ROOT}/frontend/api
    ${AVFORMAT_INCLUDE_DIR}
)

if (OPENSTREAM_BUILD_DOCK)
    target_link_libraries(openstream-beta-obs PRIVATE Qt6::Widgets)
    target_compile_definitions(openstream-beta-obs PRIVATE OPENSTREAM_HAS_CAMERA_DOCK=1)
endif()

target_link_libraries(openstream-beta-obs PRIVATE
    ${OBS_LIBRARY}
    ${AVFORMAT_LIBRARY}
    ${AVCODEC_LIBRARY}
    ${AVUTIL_LIBRARY}
    ${SWSCALE_LIBRARY}
)

if (WIN32)
    target_link_libraries(openstream-beta-obs PRIVATE ws2_32 iphlpapi)
endif()

if (UNIX AND NOT APPLE AND OPENSTREAM_BUILD_DOCK)
    target_link_libraries(openstream-beta-obs PRIVATE dl)
endif()

target_compile_definitions(openstream-beta-obs PRIVATE
    OPENSTREAM_VERSION="${PROJECT_VERSION}"
)

if (WIN32)
    set_target_properties(openstream-beta-obs PROPERTIES PREFIX "")
endif()

message(STATUS "Building OpenStream OBS source with FFmpeg SRT input support.")
message(STATUS "OpenStream OBS baseline: ${OPENSTREAM_OBS_BASELINE}; FFmpeg ABI: ${OPENSTREAM_FFMPEG_ABI}")
