# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22)
project(sheet_ncnn CXX)

if(NOT DEFINED NCNN_SOURCE_DIR)
    set(NCNN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../external/ncnn")
endif()
include("${CMAKE_CURRENT_SOURCE_DIR}/ncnn_config.cmake")
add_subdirectory("${NCNN_SOURCE_DIR}" ncnn EXCLUDE_FROM_ALL)

add_library(sheet_ncnn SHARED ncnn_jni.cpp)
target_compile_options(sheet_ncnn PRIVATE -fno-rtti -fno-exceptions -fvisibility=hidden
                                          -ffunction-sections -fdata-sections)
if(ANDROID)
    target_link_libraries(sheet_ncnn PRIVATE ncnn android log)
    target_link_options(sheet_ncnn PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
                                           -Wl,--build-id=none -Wl,-z,max-page-size=16384)
else()
    find_package(JNI REQUIRED)
    target_include_directories(sheet_ncnn PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(sheet_ncnn PRIVATE ncnn)
endif()
