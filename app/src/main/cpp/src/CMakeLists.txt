# Copyright 2016 Luca Martino.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.!

cmake_minimum_required(VERSION 3.1 FATAL_ERROR)
project(rtranslator_native)

add_subdirectory(libs/sentencepiece)

add_library(sentencepiece-interface SHARED SentencePieceProcessorInterface.cpp)
add_library(cache_container_native SHARED CacheContainerNative.cpp)
add_library(dictionary_translator_interface SHARED DictionaryTranslator.cpp protobuf/dictionary_data.pb.cc protobuf/dictionary_data.pb.h)

find_package(sqlite4java-android REQUIRED CONFIG)

# protobuf lite import
# FIND THREADS (This populates CMAKE_THREAD_LIBS_INIT) and force protobuf to use it
find_package(Threads REQUIRED)
add_definitions(-DHAVE_PTHREAD)
# Define paths
set(protobuf_source_dir "${CMAKE_CURRENT_SOURCE_DIR}/libs/protobuf-lite")
set(protobuf_cmake_dir "${protobuf_source_dir}/cmake")
# Protobuf Lite configuration variables
# These are required by the 'libprotobuf-lite.cmake' file
set(protobuf_VERSION "3.14.0")
set(LIB_PREFIX "")
set(protobuf_DEBUG_POSTFIX "")
set(protobuf_SHARED_OR_STATIC STATIC) # Usually best to build as static for Android JNI
# Include ONLY the lite library definition
# This file defines the 'libprotobuf-lite' target and its source list
include("${protobuf_cmake_dir}/libprotobuf-lite.cmake")


target_include_directories(dictionary_translator_interface PRIVATE
        #${CMAKE_CURRENT_SOURCE_DIR}/libs/sentencepiece/third_party/protobuf-lite
        ${CMAKE_CURRENT_SOURCE_DIR}/sqlite4java/include
)

target_link_libraries(dictionary_translator_interface
        libprotobuf-lite
        sqlite4java-android::sqlite4java
        android
        log
)

target_link_libraries(sentencepiece-interface
        sentencepiece-static-android
        android
        log
)

target_link_libraries(cache_container_native
        android
        log
)

add_subdirectory(bergamot_translator)
add_subdirectory(fasttext)
# ---- MagiTranslator: PLaMo 3 Translate on the CPU (llama.cpp, see tools/llama/) ----
# Built for Armv8.6-A with dotprod, i8mm and fp16 (Tensor G3/G4/G5 and other Armv9 phones): llama.cpp
# repacks the Q4_K weights at load time for its i8mm matrix kernels. The Java side checks the CPU
# features before loading libplamo_engine.so (plamo/CpuFeatures.java), so an older CPU never runs it.
if(ANDROID AND NOT DEFINED GGML_CPU_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)          # the NDK's OpenMP is not supported by llama.cpp (docs/android.md)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)    # would download KleidiAI at configure time
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
foreach(opt LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_CURL)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(_magi_shared ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)                        # llama and ggml linked into libplamo_engine.so only
add_subdirectory(libs/llama.cpp EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS ${_magi_shared})
foreach(t llama ggml ggml-base ggml-cpu)
    target_compile_options(${t} PRIVATE -O3)      # fast kernels in every build variant, debug included
endforeach()

add_library(plamo_engine SHARED plamo/plamo_engine.cpp plamo/plamo_core.cpp)
target_compile_options(plamo_engine PRIVATE -O3 -Wall)
target_link_libraries(plamo_engine llama)
if(ANDROID)
    target_link_libraries(plamo_engine android log)
endif()
