cmake_minimum_required(VERSION 3.22.1)
project(acidulous LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -ffast-math gets us flush-to-zero for denormals on ARM, which matters for
# filter and delay tails; -fno-finite-math-only keeps NaN/inf checks honest.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")
# The debug build uses the same optimisation as release, plus symbols. It's
# the build that gets played and measured, so it needs to sound and perform
# the same as what ships.
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -ffast-math -fno-finite-math-only -g")

# engine/    the audio engine
#   core/      realtime plumbing: params, queues, hand-over, timebase
#   dsp/       the shared building blocks
#   format/    reading and writing files: wav, aiff, flac, mp3, sf2
#   machine/   one directory per instrument
#   effect/    the inserts
#   modifier/   the note processors
#   rack/      what holds a machine, its effects and its fader
# sequencer/ clock, transport, clips, scenes, recording
# platform/  everything that is not portable
#   android/   the JNI bridge
#   drivers/   the Oboe stream
#   link/      the Ableton Link timebase
#
# Nothing under engine/ may include anything under platform/.
file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/engine/*.cpp")
file(GLOB_RECURSE PLATFORM_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/platform/*.cpp")
# platform/desktop belongs to the desktop build (desktop/native/CMakeLists.txt)
# and has its own AudioDriver.
list(FILTER PLATFORM_SOURCES EXCLUDE REGEX ".*/platform/desktop/.*")
# platform/web belongs to the browser build (web/engine/CMakeLists.txt).
list(FILTER PLATFORM_SOURCES EXCLUDE REGEX ".*/platform/web/.*")

add_library(acidulous SHARED
    ${ENGINE_SOURCES}
    ${PLATFORM_SOURCES}
    EngineHost.cpp
)

target_include_directories(acidulous PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}           # <engine/...>, <sequencer/...>
    ${CMAKE_CURRENT_SOURCE_DIR}/platform  # <drivers/...>
)

target_compile_options(acidulous PRIVATE -Wall -Wextra -Wno-unused-parameter)

# LAME, as its own shared library. It's LGPL, so users must be able to swap in
# their own build. See third_party/lame/PROVENANCE.md.
add_subdirectory(third_party/lame)

# Ableton Link, header-only and compiled in. It's GPLv2-or-later, which is
# compatible with our GPLv3. See third_party/link/PROVENANCE.md.
add_subdirectory(third_party/link)

# Quiet the warnings from Link's own headers.
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
