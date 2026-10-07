cmake_minimum_required(VERSION 3.22)
project(colorgrid8_host_regression LANGUAGES CXX)
add_executable(colorgrid8_decode_probe
    colorgrid8_decode_probe.cpp
    ../../main/cpp/colorgrid8_native.cpp
)
target_include_directories(colorgrid8_decode_probe PRIVATE ../../main/cpp)
target_compile_features(colorgrid8_decode_probe PRIVATE cxx_std_17)
