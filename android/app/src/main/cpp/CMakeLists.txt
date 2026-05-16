cmake_minimum_required(VERSION 3.22.1)
project(openstream_srt)

add_library(openstream_srt SHARED openstream_srt.cpp)

find_library(log-lib log)
target_link_libraries(openstream_srt ${log-lib})
