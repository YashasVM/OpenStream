cmake_minimum_required(VERSION 3.22.1)
project(openstream_srt)

add_library(openstream_srt SHARED openstream_srt.cpp)

option(OPENSTREAM_ENABLE_LIBSRT "Link the Android native sender against Android ABI-compatible libsrt" ON)

find_library(log-lib log)
target_link_libraries(openstream_srt ${log-lib})

if (OPENSTREAM_ENABLE_LIBSRT)
    set(OPENSTREAM_BUNDLED_LIBSRT_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/srt")
    set(OPENSTREAM_BUNDLED_LIBSRT_INCLUDE_DIR "${OPENSTREAM_BUNDLED_LIBSRT_ROOT}/include")
    set(OPENSTREAM_BUNDLED_LIBSRT_LIBRARY "${OPENSTREAM_BUNDLED_LIBSRT_ROOT}/lib/${ANDROID_ABI}/libsrt.a")

    if (NOT OPENSTREAM_LIBSRT_INCLUDE_DIR AND EXISTS "${OPENSTREAM_BUNDLED_LIBSRT_INCLUDE_DIR}/srt/srt.h")
        set(OPENSTREAM_LIBSRT_INCLUDE_DIR "${OPENSTREAM_BUNDLED_LIBSRT_INCLUDE_DIR}")
    endif()
    if (NOT OPENSTREAM_LIBSRT_LIBRARY AND EXISTS "${OPENSTREAM_BUNDLED_LIBSRT_LIBRARY}")
        set(OPENSTREAM_LIBSRT_LIBRARY "${OPENSTREAM_BUNDLED_LIBSRT_LIBRARY}")
    endif()

    if (NOT OPENSTREAM_LIBSRT_INCLUDE_DIR)
        find_path(OPENSTREAM_LIBSRT_INCLUDE_DIR srt/srt.h)
    endif()
    if (NOT OPENSTREAM_LIBSRT_LIBRARY)
        find_library(OPENSTREAM_LIBSRT_LIBRARY srt)
    endif()

    if (NOT OPENSTREAM_LIBSRT_INCLUDE_DIR OR NOT OPENSTREAM_LIBSRT_LIBRARY)
        message(FATAL_ERROR "Normal OpenStream APK builds require Android ABI-compatible libsrt. The bundled static libs were not found for ${ANDROID_ABI}; rebuild them or set -Popenstream.nonStreamingCiBuild=true only for source-only CI checks.")
    endif()

    target_compile_definitions(openstream_srt PRIVATE OPENSTREAM_HAVE_LIBSRT=1)
    target_include_directories(openstream_srt PRIVATE ${OPENSTREAM_LIBSRT_INCLUDE_DIR})
    target_link_libraries(openstream_srt ${OPENSTREAM_LIBSRT_LIBRARY} atomic)
else()
    target_compile_definitions(openstream_srt PRIVATE OPENSTREAM_HAVE_LIBSRT=0)
endif()
