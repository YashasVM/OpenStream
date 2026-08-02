cmake_minimum_required(VERSION 3.30)

# This probe is deliberately standalone.  It never adds FFmpeg to the product
# dependency graph: point OPENSTREAM_FFMPEG_ROOT at a pinned FFmpeg *development*
# package with include/ and lib/ directories.
project(openstream_v4_gpu_decision_probe LANGUAGES CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "The FFmpeg D3D11 probe supports Windows x64 only")
endif()

set(OPENSTREAM_FFMPEG_ROOT "$ENV{OPENSTREAM_FFMPEG_ROOT}" CACHE PATH
    "Pinned FFmpeg development package root (include/ and lib/)")
if(NOT OPENSTREAM_FFMPEG_ROOT)
  message(FATAL_ERROR
    "Set OPENSTREAM_FFMPEG_ROOT to a pinned FFmpeg development package; runtime ffmpeg.exe is insufficient")
endif()

find_path(OPENSTREAM_AVCODEC_INCLUDE_DIR libavcodec/avcodec.h
  HINTS "${OPENSTREAM_FFMPEG_ROOT}/include" NO_DEFAULT_PATH REQUIRED)
find_library(OPENSTREAM_AVCODEC_LIBRARY NAMES avcodec avcodec-62
  HINTS "${OPENSTREAM_FFMPEG_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)
find_library(OPENSTREAM_AVFORMAT_LIBRARY NAMES avformat avformat-62
  HINTS "${OPENSTREAM_FFMPEG_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)
find_library(OPENSTREAM_AVUTIL_LIBRARY NAMES avutil avutil-60
  HINTS "${OPENSTREAM_FFMPEG_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)

foreach(ffmpeg_library IN ITEMS OPENSTREAM_AVCODEC_LIBRARY OPENSTREAM_AVFORMAT_LIBRARY OPENSTREAM_AVUTIL_LIBRARY)
  if(NOT "${${ffmpeg_library}}" MATCHES "\\.lib$")
    message(FATAL_ERROR "${ffmpeg_library} must be an MSVC-compatible .lib, not ${${ffmpeg_library}}")
  endif()
endforeach()

add_executable(openstream_ffmpeg_d3d11_probe src/ffmpeg_d3d11_probe.cpp)
target_compile_features(openstream_ffmpeg_d3d11_probe PRIVATE cxx_std_20)
target_compile_definitions(openstream_ffmpeg_d3d11_probe PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(openstream_ffmpeg_d3d11_probe PRIVATE /permissive- /W4 /WX /utf-8)
target_include_directories(openstream_ffmpeg_d3d11_probe SYSTEM PRIVATE "${OPENSTREAM_AVCODEC_INCLUDE_DIR}")
target_link_libraries(openstream_ffmpeg_d3d11_probe PRIVATE
  "${OPENSTREAM_AVFORMAT_LIBRARY}"
  "${OPENSTREAM_AVCODEC_LIBRARY}"
  "${OPENSTREAM_AVUTIL_LIBRARY}"
  d3d11
  dxgi
  psapi)
file(GLOB OPENSTREAM_FFMPEG_RUNTIME_DLLS CONFIGURE_DEPENDS "${OPENSTREAM_FFMPEG_ROOT}/bin/*.dll")
add_custom_command(TARGET openstream_ffmpeg_d3d11_probe POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different ${OPENSTREAM_FFMPEG_RUNTIME_DLLS} $<TARGET_FILE_DIR:openstream_ffmpeg_d3d11_probe>
  COMMAND_EXPAND_LISTS)

add_executable(openstream_mf_gpu_probe src/mf_gpu_probe.cpp)
target_compile_features(openstream_mf_gpu_probe PRIVATE cxx_std_20)
target_compile_definitions(openstream_mf_gpu_probe PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(openstream_mf_gpu_probe PRIVATE /permissive- /W4 /WX /utf-8)
target_link_libraries(openstream_mf_gpu_probe PRIVATE
  d3d11 dxgi mf mfplat mfreadwrite mfuuid ole32 shlwapi)

include(CTest)
add_test(NAME ffmpeg_gpu_probe_help COMMAND openstream_ffmpeg_d3d11_probe --help)
add_test(NAME mf_gpu_probe_help COMMAND openstream_mf_gpu_probe --help)
