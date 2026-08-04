cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 1.0.0 LANGUAGES CXX)
set(OPENSTREAM_VERSION "${PROJECT_VERSION}" CACHE STRING "Version embedded in the OBS plugin")

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

add_library(openstream-obs MODULE
    src/async-control-client.cpp
    src/openstream-dock.cpp
    src/openstream-source.cpp
)

find_package(Qt6 6.2 COMPONENTS Network Widgets REQUIRED)
find_path(OBS_FRONTEND_INCLUDE_DIR NAMES obs-frontend-api.h
    HINTS ${OBS_ROOT} PATH_SUFFIXES frontend/api REQUIRED)
find_library(OBS_FRONTEND_LIBRARY NAMES obs-frontend-api
    HINTS ${OBS_ROOT} ${FFMPEG_ROOT}
    PATH_SUFFIXES lib lib/x64 build/frontend/api/Release REQUIRED)

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

target_include_directories(openstream-obs PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${OBS_INCLUDE_DIR}
    ${OBS_FRONTEND_INCLUDE_DIR}
    ${AVFORMAT_INCLUDE_DIR}
)

target_link_libraries(openstream-obs PRIVATE
    ${OBS_LIBRARY}
    ${OBS_FRONTEND_LIBRARY}
    Qt6::Network
    Qt6::Widgets
    ${AVFORMAT_LIBRARY}
    ${AVCODEC_LIBRARY}
    ${AVUTIL_LIBRARY}
    ${SWSCALE_LIBRARY}
)

if (WIN32)
    target_link_libraries(openstream-obs PRIVATE ws2_32 iphlpapi)
endif()

target_compile_definitions(openstream-obs PRIVATE
    OPENSTREAM_VERSION="${OPENSTREAM_VERSION}"
)

if (WIN32)
    set_target_properties(openstream-obs PROPERTIES PREFIX "")
endif()

message(STATUS "Building OpenStream OBS source with FFmpeg SRT input support.")
message(STATUS "OpenStream OBS baseline: ${OPENSTREAM_OBS_BASELINE}; FFmpeg ABI: ${OPENSTREAM_FFMPEG_ABI}")
