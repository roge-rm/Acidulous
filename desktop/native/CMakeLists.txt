cmake_minimum_required(VERSION 3.22.1)
project(acidulous_desktop LANGUAGES C CXX)

# The engine for desktop: the same sources as Android (app/src/main/cpp), with
# a desktop audio driver and log instead of Oboe and Android's.
# platform/desktop comes first on the include path, so EngineHost's
# <drivers/AudioDriver.h> and <android/log.h> resolve to the desktop versions.
# The JNI bridge is the same as Android's. Desktop-only calls (the list of
# inputs) are in desktop_jni.cpp, and ALSA sequencer MIDI is in alsa_seq.cpp.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")
set(CMAKE_C_FLAGS_RELEASE "-O2")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

# The JNI headers. Cross builds pass JNI_INCLUDE_DIRS themselves, since the
# headers are the same on every Linux and FindJNI would look for a target JVM.
if(NOT JNI_INCLUDE_DIRS)
    find_package(JNI REQUIRED)
endif()

# The Pi build is tuned for the Pi 4's core and still runs on a Pi 5. The
# x86-64 build targets any x86-64.
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    add_compile_options(-mcpu=cortex-a72)
endif()

file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS "${CPP}/engine/*.cpp")

# Windows, cross-compiled with MinGW-w64 (Dockerfile.windows). There's no ALSA
# there, and the app skips its missing JNI calls.
if(WIN32)
    # ASIO support for audio interfaces' low-latency drivers, using the host
    # side of Steinberg's SDK (third_party/asiosdk, under the GPL v3).
    set(ASIOSDK ${CPP}/third_party/asiosdk)
    set(PLATFORM_SOURCES ${CPP}/platform/link/LinkTimebase.cpp
        ${CPP}/platform/desktop/drivers/Asio.cpp
        ${ASIOSDK}/common/asio.cpp
        ${ASIOSDK}/host/asiodrivers.cpp
        ${ASIOSDK}/host/pc/asiolist.cpp)
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
# ALSA headers only. libasound is opened at run time (alsa_seq.cpp), like
# miniaudio does, so nothing links against it. dl is linked below.
target_include_directories(acidulous SYSTEM PRIVATE ${CPP}/third_party/alsa/include)
target_compile_options(acidulous PRIVATE -Wall -Wextra -Wno-unused-parameter)
set_source_files_properties(${CPP}/platform/link/LinkTimebase.cpp
    PROPERTIES COMPILE_OPTIONS "-Wno-multichar;-Wno-unused-parameter")
# miniaudio is C that also compiles as C++. Its warnings aren't ours.
set_source_files_properties(${CPP}/platform/desktop/drivers/AudioDriver.cpp
    PROPERTIES COMPILE_OPTIONS "-w")

add_subdirectory(${CPP}/third_party/lame lame)
# Built next to the engine, so $ORIGIN finds it in the build tree the same
# way as when installed.
set_target_properties(mp3lame PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
add_subdirectory(${CPP}/third_party/link link)

find_package(Threads REQUIRED)
# LAME stays a separate shared library, like on Android, because of the LGPL
# (see its PROVENANCE.md). It's found next to this one.
if(WIN32)
    # acidulous.dll and mp3lame.dll, the names Java's loadLibrary looks for,
    # with MinGW's C++ and thread runtimes linked in so they're the only two
    # files to ship. Windows 10 is the minimum (the JVM's own minimum).
    set_target_properties(acidulous mp3lame PROPERTIES PREFIX "")
    set_target_properties(mp3lame PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
    target_compile_definitions(acidulous PRIVATE _WIN32_WINNT=0x0A00 WINVER=0x0A00 NOMINMAX WIN32_LEAN_AND_MEAN)
    target_link_libraries(acidulous mp3lame ableton_link Threads::Threads ole32 advapi32 user32)
    target_include_directories(acidulous SYSTEM PRIVATE ${ASIOSDK}/common ${ASIOSDK}/host ${ASIOSDK}/host/pc)
    # Steinberg's code, built with its warnings off. It and the code using it
    # need COM, which WIN32_LEAN_AND_MEAN leaves out.
    set_source_files_properties(${ASIOSDK}/common/asio.cpp ${ASIOSDK}/host/asiodrivers.cpp ${ASIOSDK}/host/pc/asiolist.cpp
        PROPERTIES COMPILE_OPTIONS "-w;-UWIN32_LEAN_AND_MEAN")
    set_source_files_properties(${CPP}/platform/desktop/drivers/Asio.cpp PROPERTIES COMPILE_OPTIONS "-UWIN32_LEAN_AND_MEAN")
    target_link_options(acidulous PRIVATE -static -static-libgcc -static-libstdc++)
    target_link_options(mp3lame PRIVATE -static -static-libgcc)

    # Acidulous.exe, which starts the app on the Java runtime next to it
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
# Only $ORIGIN, even in the build tree. The packages put the two libraries
# side by side, and a path into this machine's build folder is useless on a Pi.
set_target_properties(acidulous PROPERTIES BUILD_WITH_INSTALL_RPATH TRUE INSTALL_RPATH "$ORIGIN")
