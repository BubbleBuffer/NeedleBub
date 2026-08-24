cmake_minimum_required(VERSION 3.22.1)
project(needlebub_runtime LANGUAGES C CXX)

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/vendor/needle/libneedle.a"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/vendor/needle")

add_library(needlebub_runtime SHARED needle_jni.cpp)
target_include_directories(needlebub_runtime PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/needle")
target_compile_options(needlebub_runtime PRIVATE -Wall -Wextra -Werror)

find_library(android_log log)
find_library(android_lib android)
find_library(dl_lib dl)

target_link_libraries(needlebub_runtime PRIVATE needle ${android_log} ${android_lib} ${dl_lib} m)
