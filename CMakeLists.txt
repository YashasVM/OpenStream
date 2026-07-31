cmake_minimum_required(VERSION 3.30)

project(OpenStream VERSION 4.0.0 LANGUAGES CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "OpenStream V4 currently supports Windows x64 only")
endif()

set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")

include(CTest)
find_package(nlohmann_json 3.12.0 CONFIG REQUIRED)

add_executable(openstream_foundation_smoke cmake/tests/foundation_smoke.cpp)
target_compile_features(openstream_foundation_smoke PRIVATE cxx_std_20)
target_compile_definitions(openstream_foundation_smoke PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(openstream_foundation_smoke PRIVATE /permissive- /W4 /WX /utf-8)
target_link_libraries(openstream_foundation_smoke PRIVATE nlohmann_json::nlohmann_json)

add_test(NAME windows_foundation_smoke COMMAND openstream_foundation_smoke)
add_test(
  NAME engine_has_no_obs_dependency
  COMMAND powershell -NoProfile -ExecutionPolicy Bypass
          -File "${CMAKE_SOURCE_DIR}/cmake/verify-engine-isolation.ps1"
          -RepositoryRoot "${CMAKE_SOURCE_DIR}"
)
