cmake_minimum_required(VERSION 3.22.1)
project(irl_mux)
add_library(irl_mux SHARED irl_mux.cpp)
target_link_options(irl_mux PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
