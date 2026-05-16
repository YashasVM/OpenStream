cmake_minimum_required(VERSION 3.24)
project(openstream_obs_plugin VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(openstream-obs MODULE
    src/openstream-source.cpp
)

target_include_directories(openstream-obs PRIVATE
    ${OBS_ROOT}/include
)

target_compile_definitions(openstream-obs PRIVATE
    OPENSTREAM_VERSION="${PROJECT_VERSION}"
)

if (WIN32)
    set_target_properties(openstream-obs PROPERTIES PREFIX "")
endif()

message(STATUS "Set OBS_ROOT, FFMPEG_ROOT, and SRT_ROOT to build the full plugin against installed SDKs.")
