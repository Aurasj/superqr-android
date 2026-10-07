cmake_minimum_required(VERSION 3.22.1)

project("colorgrid8_native" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(colorgrid8_native SHARED
    colorgrid8_native.cpp
    macrochroma_native.cpp
    colorgrid8_jni.cpp
)

target_include_directories(colorgrid8_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if (ANDROID)
    target_compile_options(colorgrid8_native PRIVATE
        -O3
        -ffast-math
        -Wall
        -Wextra
    )
    target_link_libraries(colorgrid8_native PRIVATE
        log
    )
endif()
