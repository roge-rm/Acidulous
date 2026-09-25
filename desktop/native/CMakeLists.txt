cmake_minimum_required(VERSION 3.22.1)
project(acidulous_desktop LANGUAGES C CXX)

# The engine for the desktop: the same sources as the phone's
# (app/src/main/cpp), with the desktop's own audio driver and log in place of
# Oboe's and Android's. platform/desktop comes first on the include path, so
# EngineHost's <drivers/AudioDriver.h> and <android/log.h> are the desktop's.
# The JNI bridge is the phone's, unchanged: JNI is JNI on any JVM. What only
# the desktop asks - the list of inputs - is in desktop_jni.cpp.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")
set(CMAKE_C_FLAGS_RELEASE "-O2")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

# The JNI headers. A cross build names them itself (JNI_INCLUDE_DIRS): they
# are the same on every Linux, and FindJNI would go looking for a target JVM.
if(NOT JNI_INCLUDE_DIRS)
    find_package(JNI REQUIRED)
endif()

# The Pi build is tuned for a Pi 4's core and still runs on a Pi 5's; the
# desktop's is for any x86-64.
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    add_compile_options(-mcpu=cortex-a72)
endif()

file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS "${CPP}/engine/*.cpp")

add_library(acidulous SHARED
    ${ENGINE_SOURCES}
    ${CPP}/EngineHost.cpp
    ${CPP}/platform/android/jni_bridge.cpp
    ${CPP}/platform/link/LinkTimebase.cpp
    ${CPP}/platform/desktop/drivers/AudioDriver.cpp
    ${CPP}/platform/desktop/desktop_jni.cpp
)

target_include_directories(acidulous PRIVATE
    ${CPP}/platform/desktop       # <drivers/AudioDriver.h>, <android/log.h>
    ${CPP}                        # <engine/...>, <sequencer/...>
    ${CPP}/platform               # <link/...>
    ${CPP}/third_party/miniaudio
    ${JNI_INCLUDE_DIRS}
)
target_compile_options(acidulous PRIVATE -Wall -Wextra -Wno-unused-parameter)
set_source_files_properties(${CPP}/platform/link/LinkTimebase.cpp
    PROPERTIES COMPILE_OPTIONS "-Wno-multichar;-Wno-unused-parameter")
# miniaudio is C written to compile as C++ too; its warnings are not ours.
set_source_files_properties(${CPP}/platform/desktop/drivers/AudioDriver.cpp
    PROPERTIES COMPILE_OPTIONS "-w")

add_subdirectory(${CPP}/third_party/lame lame)
# Built beside the engine, so $ORIGIN finds it in the build tree as it does
# once installed.
set_target_properties(mp3lame PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
add_subdirectory(${CPP}/third_party/link link)

find_package(Threads REQUIRED)
# LAME stays its own shared library, as on the phone, for the LGPL's sake (see
# its PROVENANCE.md); it is found beside this one.
target_link_libraries(acidulous mp3lame ableton_link Threads::Threads ${CMAKE_DL_LIBS} m)
# Only $ORIGIN, even in the build tree: the packages copy the two libraries
# side by side, and a path into this machine's build folder means nothing on a Pi.
set_target_properties(acidulous PROPERTIES BUILD_WITH_INSTALL_RPATH TRUE INSTALL_RPATH "$ORIGIN")
