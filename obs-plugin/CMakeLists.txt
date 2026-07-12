cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 2.0.0 LANGUAGES CXX)

set(OPENSTREAM_VERSION "${PROJECT_VERSION}" CACHE STRING "Version embedded in the OBS plugin")

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

# The control dock uses OBS 30+'s stable frontend dock API and Qt 6 Widgets.
# OBS_ROOT must therefore point to an OBS development tree/SDK containing
# frontend/api/obs-frontend-api.h and the obs-frontend-api import library.
find_package(Qt6 REQUIRED COMPONENTS Network Widgets)
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
