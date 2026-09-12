cmake_minimum_required(VERSION 3.22.1)
project(acidulous LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -ffast-math gets us flush-to-zero for denormals on ARM, which matters for
# filter and delay tails; -fno-finite-math-only keeps NaN/inf checks honest.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")
set(CMAKE_CXX_FLAGS_DEBUG "-O1 -g")

# engine/    the audio engine: core, dsp, machines, effects, eventors, racks
# sequencer/ clock, transport, clips, scenes, recording
# platform/  Android: the Oboe stream
file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/engine/*.cpp")
file(GLOB_RECURSE PLATFORM_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/platform/*.cpp")

add_library(acidulous SHARED
    ${ENGINE_SOURCES}
    ${PLATFORM_SOURCES}
    EngineHost.cpp
    jni_bridge.cpp
)

target_include_directories(acidulous PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}           # <engine/...>, <sequencer/...>
    ${CMAKE_CURRENT_SOURCE_DIR}/platform  # <drivers/...>
)

target_compile_options(acidulous PRIVATE -Wall -Wextra -Wno-unused-parameter)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(acidulous
    oboe::oboe
    android
    log
)
