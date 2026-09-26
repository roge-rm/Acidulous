cmake_minimum_required(VERSION 3.22.1)
project(acidulous_web LANGUAGES C CXX)

# The engine for the browser: the phone's sources (app/src/main/cpp) as
# WebAssembly, with the browser's own audio driver, log and a Link that is
# never on. platform/web comes first on the include path, so EngineHost's
# <drivers/AudioDriver.h> and <android/log.h> are the web's.
#
# Threads and shared memory: the UI calls into the very engine the audio
# worklet renders from, as it does on the phone, which is why the page must
# be cross-origin isolated (web/app/third_party/coi-serviceworker).

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(ACID_OPT "-O3" CACHE STRING "the engine's release flags")
set(CMAKE_CXX_FLAGS_RELEASE "${ACID_OPT}")
set(CMAKE_C_FLAGS_RELEASE "-O2")

set(CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")
file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS "${CPP}/engine/*.cpp")

add_executable(acidulous
    ${ENGINE_SOURCES}
    ${CPP}/EngineHost.cpp
    ${CPP}/platform/web/drivers/AudioDriver.cpp
    ${CPP}/platform/web/LinkOff.cpp
    ${CPP}/platform/web/web_bridge.cpp
    # The long calls, handed to threads of their own (tools/gen_engine_bridge.py).
    ${CPP}/platform/web/web_async.cpp
    # The phone's own bridge, against platform/web/jni.h: every engine call
    # the app makes, compiled here unchanged.
    ${CPP}/platform/android/jni_bridge.cpp
)
target_include_directories(acidulous PRIVATE
    ${CPP}/platform/web       # <drivers/AudioDriver.h>, <jni.h>, <android/log.h>
    ${CPP}                    # <engine/...>, <sequencer/...>
    ${CPP}/platform           # <link/LinkTimebase.h>
)

# LAME is built in here: a WebAssembly module links one way only. The app is
# GPLv3, which LGPL code may be taken under, and its source is published.
# Its CMakeLists asks for a shared library, which is right on the phone and
# would be dynamic linking here - which the audio worklet does not support.
set_property(GLOBAL PROPERTY TARGET_SUPPORTS_SHARED_LIBS FALSE)
add_subdirectory(${CPP}/third_party/lame lame)
target_link_libraries(acidulous mp3lame)

set(THREADS -pthread)
target_compile_options(acidulous PRIVATE ${THREADS} -Wno-unused-parameter)
# WebAssembly cannot set the CPU's flush-to-zero, so the filters and feedback
# paths that decay flush their own tiny values instead: dsp::guardDenormal.
target_compile_definitions(acidulous PRIVATE ACID_SOFT_DENORMALS)
target_compile_options(mp3lame PRIVATE ${THREADS})
target_link_options(acidulous PRIVATE
    ${THREADS}
    -sAUDIO_WORKLET=1
    -sWASM_WORKERS=1
    -sMODULARIZE=1
    -sEXPORT_NAME=AcidEngine
    -sENVIRONMENT=web,worker
    -sINITIAL_MEMORY=256MB
    -sALLOW_MEMORY_GROWTH=1
    # The audio's stand-in and the engine's own workers, and the long calls
    # the page hands over, each on a thread (web_async.cpp); more are made as
    # needed, which a page only waits for the first time.
    -sPTHREAD_POOL_SIZE=8
    -sSTACK_SIZE=1MB
    "--pre-js=${CMAKE_CURRENT_SOURCE_DIR}/worklet-clock.js"
    # The file system, which the app's Kotlin shares with the engine (FS), and
    # the browser storage the page keeps the app's folder in (IDBFS).
    -sFORCE_FILESYSTEM=1
    -lidbfs.js
    "-sEXPORTED_RUNTIME_METHODS=['ccall','cwrap','UTF8ToString','stringToUTF8','lengthBytesUTF8','FS','IDBFS']"
    "-sEXPORTED_FUNCTIONS=['_malloc','_free']"
)
# Relinked when the worklet's clock changes, which CMake cannot see for itself.
set_target_properties(acidulous PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/worklet-clock.js")
