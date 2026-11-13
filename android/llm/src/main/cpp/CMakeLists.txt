# Ghost Keyboard's on-phone language model: llama.cpp, fetched at its release tag and built static into one JNI
# library, libdevkeyboard_llm.so (llm_jni.cpp over llm_core.cpp). Only the keyboard's model process loads it
# (LlmService.kt); tool/local_ai_try builds the same llm_core.cpp on a desk.
cmake_minimum_required(VERSION 3.18)
project(devkeyboard_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
include(FetchContent)

# A debug build of the app still gets the model built for speed, as whisper is (app/src/main/cpp/CMakeLists.txt): at
# the toolchain's -O0 a rewrite of one line would take minutes.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

# The library and nothing else. Under FetchContent llama.cpp already leaves out its tools, examples, tests, server
# and common library; these are the ones it would otherwise decide for itself.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # The phones this runs on (2019 on), as for whisper: half floats and dot products, which a quantised model's
  # matrix work leans on.
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_Declare(
  llama
  GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
  GIT_TAG v0.9.0
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

# The app's size (Matt, 2026-11-12: "as long as it's not too large we don't want to bloat the app size"). llama.cpp's
# own code is reading a model, its vocabulary, sampling and the wiring of every architecture it knows: none of it is
# where a rewrite's time goes, which is ggml's arithmetic. So the first is built for size and the second keeps its
# speed. docs/local-ai.md has what each choice weighs.
target_compile_options(llama PRIVATE -Os)

# A tag can be moved; a commit cannot. This is the commit v0.9.0 named when llm_core.cpp was written against it.
execute_process(COMMAND git -C ${llama_SOURCE_DIR} rev-parse HEAD OUTPUT_VARIABLE LLAMA_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT LLAMA_HEAD STREQUAL "cd959bae99ff4bed22bef2604f45241c09bbb8c8")
  message(FATAL_ERROR "llama.cpp's v0.9.0 is not the commit this was written against: ${LLAMA_HEAD}")
endif()

add_library(devkeyboard_llm SHARED llm_jni.cpp llm_core.cpp)
target_link_libraries(devkeyboard_llm PRIVATE llama log android)
target_compile_options(devkeyboard_llm PRIVATE -O3 -fvisibility=hidden)
target_link_options(devkeyboard_llm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
