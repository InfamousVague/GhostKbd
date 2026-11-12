# Dev Keyboard's speech to text: whisper.cpp, fetched at its release tag and built static into one small JNI library,
# libdevkeyboard_whisper.so (whisper_jni.cpp). The dictation plugin loads it; nothing else in the app does.
cmake_minimum_required(VERSION 3.18)
project(devkeyboard_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
include(FetchContent)

# A debug build of the app still gets whisper built for speed: at the toolchain's -O0 it is about ten times slower,
# which on an emulator made a two-second take wait six seconds or more, and makes dictation's words as they are
# spoken (LiveWords.kt) impossible to try there. Release builds are as they were.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # The phones this runs on (2019 on): half floats and dot products, which whisper's matrix work leans on.
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
  GIT_TAG v1.9.5
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(devkeyboard_whisper SHARED whisper_jni.cpp)
target_link_libraries(devkeyboard_whisper PRIVATE whisper log android)
target_compile_options(devkeyboard_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(devkeyboard_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
