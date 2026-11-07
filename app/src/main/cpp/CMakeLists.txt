cmake_minimum_required(VERSION 3.22.1)
project(stringpage_rse LANGUAGES CXX)
add_library(stringpage_rse SHARED source_rate_jni.cpp r8brain/r8bbase.cpp)
target_compile_features(stringpage_rse PRIVATE cxx_std_17)
target_include_directories(stringpage_rse PRIVATE r8brain)
# Preserve the original double operation order on both x86 and ARM.
target_compile_options(stringpage_rse PRIVATE -ffp-contract=off -fno-fast-math)
target_link_options(stringpage_rse PRIVATE -Wl,-z,max-page-size=16384)
