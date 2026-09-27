cmake_minimum_required(VERSION 3.22.1)
project(acidulous_desktop LANGUAGES C CXX)

# The engine for the desktop: the same sources as the phone's
# (app/src/main/cpp), with the desktop's own audio driver and log in place of
# Oboe's and Android's. platform/desktop comes first on the include path, so
# EngineHost's <drivers/AudioDriver.h> and <android/log.h> are the desktop's.
# The JNI bridge is the phone's, unchanged: JNI is JNI on any JVM. What only
# the desktop asks - the list of inputs - is in desktop_jni.cpp, and MIDI
# through ALSA's sequencer in alsa_seq.cpp.

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

# Windows, cross-compiled with MinGW-w64 (Dockerfile.windows): no ALSA, whose
# JNI calls the app finds missing and passes over, and Link switched off -
# the browser's stand-in - until Link's Windows platform headers are vendored
# beside the Linux ones.
if(WIN32)
    set(PLATFORM_SOURCES ${CPP}/platform/web/LinkOff.cpp)
else()
    set(PLATFORM_SOURCES ${CPP}/platform/link/LinkTimebase.cpp ${CPP}/platform/desktop/alsa_seq.cpp)
endif()

add_library(acidulous SHARED
    ${ENGINE_SOURCES}
    ${CPP}/EngineHost.cpp
    ${CPP}/platform/android/jni_bridge.cpp
    ${CPP}/platform/desktop/drivers/AudioDriver.cpp
    ${CPP}/platform/desktop/desktop_jni.cpp
    ${PLATFORM_SOURCES}
)

target_include_directories(acidulous PRIVATE
    ${CPP}/platform/desktop       # <drivers/AudioDriver.h>, <android/log.h>
    ${CPP}                        # <engine/...>, <sequencer/...>
    ${CPP}/platform               # <link/...>
    ${CPP}/third_party/miniaudio
    ${JNI_INCLUDE_DIRS}
)
# ALSA's headers only: libasound itself is opened at run time (alsa_seq.cpp),
# as miniaudio opens it, so nothing here links against it; dl is linked below.
target_include_directories(acidulous SYSTEM PRIVATE ${CPP}/third_party/alsa/include)
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
if(WIN32)
    # acidulous.dll and mp3lame.dll, the names Java's loadLibrary looks for,
    # with MinGW's C++ and threads runtimes linked in so the two are all there
    # is to ship. Windows 10 is the oldest the app runs on (the JVM's own floor).
    set_target_properties(acidulous mp3lame PROPERTIES PREFIX "")
    set_target_properties(mp3lame PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
    target_compile_definitions(acidulous PRIVATE _WIN32_WINNT=0x0A00 WINVER=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
    target_link_libraries(acidulous mp3lame Threads::Threads)
    target_link_options(acidulous PRIVATE -static -static-libgcc -static-libstdc++)
    target_link_options(mp3lame PRIVATE -static -static-libgcc)

    # Acidulous.exe, which starts the app on the Java runtime beside it
    # (desktop/windows/launcher.c), with the app's icon.
    enable_language(RC)
    set(ACIDULOUS_VERSION "0.0" CACHE STRING "The version the launcher reports")
    set(LAUNCHER_ICON ${CMAKE_CURRENT_SOURCE_DIR}/../windows/acidulous.ico)
    configure_file(../windows/launcher.rc.in ${CMAKE_BINARY_DIR}/launcher.rc @ONLY)
    add_executable(launcher WIN32 ../windows/launcher.c ${CMAKE_BINARY_DIR}/launcher.rc)
    set_target_properties(launcher PROPERTIES OUTPUT_NAME Acidulous)
    target_compile_definitions(launcher PRIVATE _WIN32_WINNT=0x0A00 UNICODE _UNICODE)
    target_link_options(launcher PRIVATE -municode -static)
    target_link_libraries(launcher shell32)
else()
    target_link_libraries(acidulous mp3lame ableton_link Threads::Threads ${CMAKE_DL_LIBS} m)
endif()
# Only $ORIGIN, even in the build tree: the packages copy the two libraries
# side by side, and a path into this machine's build folder means nothing on a Pi.
set_target_properties(acidulous PROPERTIES BUILD_WITH_INSTALL_RPATH TRUE INSTALL_RPATH "$ORIGIN")
