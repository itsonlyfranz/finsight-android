cmake_minimum_required(VERSION 3.22)

project("finsight_llm" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# On-device inference runtime: llama.cpp (GGUF).
#
# Pinned to an upstream release tag. v0.9.0 is the first line that runs every model family the
# app offers: Gemma 4 (arch gemma4), Liquid LFM3 (arch lfm3, needs >= b11960) and the 1-/2-bit
# Bonsai GGUF types (GGML_TYPE_Q1_0 / GGML_TYPE_Q2_0, needs >= b11556).
# The source is fetched at configure time (and cached in the build tree) so the repository
# does not vendor ~50 MB of third-party code.
# ---------------------------------------------------------------------------
set(FINSIGHT_LLAMA_CPP_TAG "v0.9.0" CACHE STRING "llama.cpp release tag to build")
set(FINSIGHT_LLAMA_CPP_DIR "" CACHE PATH "Optional local llama.cpp checkout (skips the download)")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    # KleidiAI micro-kernels are chosen at run time from CPU features (dotprod / i8mm / SME).
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported ABI for on-device inference: ${ANDROID_ABI}")
endif()

if(FINSIGHT_LLAMA_CPP_DIR)
    set(LLAMA_SRC "${FINSIGHT_LLAMA_CPP_DIR}")
    add_subdirectory(${LLAMA_SRC} build-llama)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${FINSIGHT_LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_SRC "${llama_cpp_SOURCE_DIR}")
endif()

add_library(finsight_llm SHARED finsight_llm.cpp)

target_include_directories(finsight_llm PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(finsight_llm PRIVATE llama llama-common android log)
