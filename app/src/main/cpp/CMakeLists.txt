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

# LAME, as a shared library of its own. It is LGPL, so it must stay
# replaceable: linked in statically nobody could swap their own build in, and
# that relinking freedom is the whole point of that licence. See
# third_party/lame/PROVENANCE.md.
add_subdirectory(third_party/lame)

# Ableton Link, as headers. GPLv2-or-later, which our GPLv3 may take, so
# unlike LAME it is compiled straight in - there is no relinking freedom to
# preserve and a header-only library has no other way to be used. See
# third_party/link/PROVENANCE.md.
add_subdirectory(third_party/link)

# Link's own headers are somebody else's and are warned about on their terms.
set_source_files_properties(
    ${CMAKE_CURRENT_SOURCE_DIR}/platform/link/LinkTimebase.cpp
    PROPERTIES COMPILE_OPTIONS "-Wno-multichar;-Wno-unused-parameter"
)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(acidulous
    oboe::oboe
    mp3lame
    ableton_link
    android
    log
)
