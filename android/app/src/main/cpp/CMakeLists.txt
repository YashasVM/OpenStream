cmake_minimum_required(VERSION 3.22.1)
project(openstream_srt)

add_library(openstream_srt SHARED openstream_srt.cpp)

option(OPENSTREAM_ENABLE_LIBSRT "Link the Android native sender against Android ABI-compatible libsrt" ON)

find_library(log-lib log)
target_link_libraries(openstream_srt ${log-lib})

if (OPENSTREAM_ENABLE_LIBSRT)
    find_path(OPENSTREAM_LIBSRT_INCLUDE_DIR srt/srt.h)
    find_library(OPENSTREAM_LIBSRT_LIBRARY srt)

    if (NOT OPENSTREAM_LIBSRT_INCLUDE_DIR OR NOT OPENSTREAM_LIBSRT_LIBRARY)
        message(FATAL_ERROR "Normal OpenStream APK builds require Android ABI-compatible libsrt. Provide OPENSTREAM_LIBSRT_INCLUDE_DIR and OPENSTREAM_LIBSRT_LIBRARY, or set -Popenstream.nonStreamingCiBuild=true for source-only CI checks.")
    endif()

    target_compile_definitions(openstream_srt PRIVATE OPENSTREAM_HAVE_LIBSRT=1)
    target_include_directories(openstream_srt PRIVATE ${OPENSTREAM_LIBSRT_INCLUDE_DIR})
    target_link_libraries(openstream_srt ${OPENSTREAM_LIBSRT_LIBRARY})
else()
    target_compile_definitions(openstream_srt PRIVATE OPENSTREAM_HAVE_LIBSRT=0)
endif()
