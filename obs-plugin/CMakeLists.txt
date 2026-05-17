cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED OBS_ROOT)
    message(FATAL_ERROR "Set OBS_ROOT to the OBS Studio install or development SDK root.")
endif()

add_library(openstream-obs MODULE
    src/openstream-source.cpp
)

target_include_directories(openstream-obs PRIVATE
    ${OBS_ROOT}/include
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
    HINTS ${OBS_ROOT}
    PATH_SUFFIXES lib lib/x64 bin/64bit bin/64bit/obs
    REQUIRED
)

target_include_directories(openstream-obs PRIVATE
    ${AVFORMAT_INCLUDE_DIR}
)

target_link_libraries(openstream-obs PRIVATE
    ${OBS_LIBRARY}
    ${AVFORMAT_LIBRARY}
    ${AVCODEC_LIBRARY}
    ${AVUTIL_LIBRARY}
    ${SWSCALE_LIBRARY}
)

target_compile_definitions(openstream-obs PRIVATE
    OPENSTREAM_VERSION="${PROJECT_VERSION}"
)

if (WIN32)
    set_target_properties(openstream-obs PROPERTIES PREFIX "")
endif()

message(STATUS "Building OpenStream OBS source with FFmpeg SRT input support.")
