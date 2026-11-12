# The keyboard's on-phone model, tried on a desk: the same llm_core.cpp the phone runs (android/llm), built with
# llama.cpp at the tag android/llm/src/main/cpp/CMakeLists.txt names, as a small command-line tool.
#
#   cmake -S tool/local_ai_try -B /tmp/local_ai_try -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/local_ai_try -j
#   /tmp/local_ai_try/local_ai_try <model.gguf> <system.txt> <text.txt> [max tokens] [temperature] [threads] [seed]
#
# -DLLAMA_SRC=<a checkout of llama.cpp> uses that in place of fetching one.
cmake_minimum_required(VERSION 3.18)
project(local_ai_try C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
if(LLAMA_SRC)
  add_subdirectory(${LLAMA_SRC} llama)
else()
  include(FetchContent)
  FetchContent_Declare(llama GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG v0.9.0 GIT_SHALLOW TRUE)
  FetchContent_MakeAvailable(llama)
endif()
set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../android/llm/src/main/cpp)
add_executable(local_ai_try main.cpp ${CORE}/llm_core.cpp)
target_include_directories(local_ai_try PRIVATE ${CORE})
target_link_libraries(local_ai_try PRIVATE llama)
