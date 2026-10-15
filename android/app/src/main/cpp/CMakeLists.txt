cmake_minimum_required(VERSION 3.22.1)
project(needlebub_runtime LANGUAGES C CXX)

option(NEEDLEBUB_EMULATOR_RUNTIME_STUB "Build a non-inferencing x86_64 library for Android listener tests" OFF)

# UPSTREAM.json is the single source of engine identity. The header and archive it names
# must match their pinned SHA-256 before anything is compiled or linked against them.
set(NEEDLE_VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/needle")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${NEEDLE_VENDOR_DIR}/UPSTREAM.json"
    "${NEEDLE_VENDOR_DIR}/needle.h"
    "${NEEDLE_VENDOR_DIR}/libneedle.a")
file(READ "${NEEDLE_VENDOR_DIR}/UPSTREAM.json" NEEDLE_PIN_JSON)
string(JSON NEEDLE_ENGINE_ABI GET "${NEEDLE_PIN_JSON}" engineAbi)
string(JSON NEEDLE_LIB_SHA256 GET "${NEEDLE_PIN_JSON}" libneedleSha256)
string(JSON NEEDLE_HEADER_SHA256 GET "${NEEDLE_PIN_JSON}" headerSha256)

file(SHA256 "${NEEDLE_VENDOR_DIR}/needle.h" NEEDLE_HEADER_ACTUAL_SHA256)
if(NOT NEEDLE_HEADER_ACTUAL_SHA256 STREQUAL NEEDLE_HEADER_SHA256)
    message(FATAL_ERROR "vendor/needle/needle.h sha256 ${NEEDLE_HEADER_ACTUAL_SHA256} does not match UPSTREAM.json headerSha256 ${NEEDLE_HEADER_SHA256}")
endif()

if(NEEDLEBUB_EMULATOR_RUNTIME_STUB)
    add_library(needle STATIC needle_emulator_stub.cpp)
    target_include_directories(needle PUBLIC "${NEEDLE_VENDOR_DIR}")
else()
    file(SHA256 "${NEEDLE_VENDOR_DIR}/libneedle.a" NEEDLE_LIB_ACTUAL_SHA256)
    if(NOT NEEDLE_LIB_ACTUAL_SHA256 STREQUAL NEEDLE_LIB_SHA256)
        message(FATAL_ERROR "vendor/needle/libneedle.a sha256 ${NEEDLE_LIB_ACTUAL_SHA256} does not match UPSTREAM.json libneedleSha256 ${NEEDLE_LIB_SHA256}")
    endif()
    add_library(needle STATIC IMPORTED)
    set_target_properties(needle PROPERTIES
        IMPORTED_LOCATION "${NEEDLE_VENDOR_DIR}/libneedle.a"
        INTERFACE_INCLUDE_DIRECTORIES "${NEEDLE_VENDOR_DIR}")
endif()

add_library(needlebub_runtime SHARED needle_jni.cpp)
target_include_directories(needlebub_runtime PRIVATE "${NEEDLE_VENDOR_DIR}")
target_compile_definitions(needlebub_runtime PRIVATE "NEEDLEBUB_ENGINE_ABI=\"${NEEDLE_ENGINE_ABI}\"")
target_compile_options(needlebub_runtime PRIVATE -Wall -Wextra -Werror)
# NDK r23 does not default to the alignment required by 16 KB Android devices.
target_link_options(needlebub_runtime PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

find_library(android_log log)
find_library(android_lib android)
find_library(dl_lib dl)

target_link_libraries(needlebub_runtime PRIVATE needle ${android_log} ${android_lib} ${dl_lib} m)
