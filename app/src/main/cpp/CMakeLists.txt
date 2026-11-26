cmake_minimum_required(VERSION 3.22.1)
project(lumisynth LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe comes from the Maven AAR via prefab.
find_package(oboe REQUIRED CONFIG)

# FluidSynth prebuilt (official v2.6.2 Android bundle). Its codec dependencies sit
# next to it in jniLibs and are packaged from there.
add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libfluidsynth.so
        INTERFACE_INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/third_party/fluidsynth/include)

add_library(lumisynth SHARED
        AudioEngine.cpp
        LoopSequencer.cpp
        jni_bridge.cpp)

target_compile_options(lumisynth PRIVATE -Wall -Wextra)
target_link_libraries(lumisynth PRIVATE fluidsynth oboe::oboe log)
