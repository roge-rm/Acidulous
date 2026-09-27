cmake_minimum_required(VERSION 3.22.1)
project(acidulous_web LANGUAGES C CXX)

# The engine for the browser: the phone's sources (app/src/main/cpp) built as
# WebAssembly, with the web audio driver, log and a Link that's always off.
# platform/web comes first on the include path, so EngineHost gets the web's
# <drivers/AudioDriver.h> and <android/log.h>.
#
# The UI calls into the same engine the audio worklet renders from, so memory
# is shared between threads and the page has to be cross-origin isolated
# (web/app/src/wasmJsMain/resources/sw.js).

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
    # Long calls, each run on its own thread (tools/gen_engine_bridge.py).
    ${CPP}/platform/web/web_async.cpp
    # The Android JNI bridge, compiled unchanged against platform/web/jni.h.
    ${CPP}/platform/android/jni_bridge.cpp
)
target_include_directories(acidulous PRIVATE
    ${CPP}/platform/web       # <drivers/AudioDriver.h>, <jni.h>, <android/log.h>
    ${CPP}                    # <engine/...>, <sequencer/...>
    ${CPP}/platform           # <link/LinkTimebase.h>
)

# LAME is linked in statically. A WebAssembly module can't link dynamically in
# the audio worklet, so shared libraries are turned off here. LAME is LGPL,
# which is fine for a GPLv3 app with published source.
set_property(GLOBAL PROPERTY TARGET_SUPPORTS_SHARED_LIBS FALSE)
add_subdirectory(${CPP}/third_party/lame lame)
target_link_libraries(acidulous mp3lame)

set(THREADS -pthread)
target_compile_options(acidulous PRIVATE ${THREADS} -Wno-unused-parameter)
# WebAssembly can't set the CPU's flush-to-zero, so decaying filters and
# feedback paths flush tiny values themselves with dsp::guardDenormal.
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
    # Threads for the audio, the engine's workers and the long calls from the
    # page (web_async.cpp). More are made if needed, and the page only waits
    # for that the first time.
    -sPTHREAD_POOL_SIZE=8
    -sSTACK_SIZE=1MB
    "--pre-js=${CMAKE_CURRENT_SOURCE_DIR}/worklet-clock.js"
    # The file system the app's Kotlin shares with the engine (FS), and the
    # browser storage the app's folder is kept in (IDBFS).
    -sFORCE_FILESYSTEM=1
    -lidbfs.js
    "-sEXPORTED_RUNTIME_METHODS=['ccall','cwrap','UTF8ToString','stringToUTF8','lengthBytesUTF8','FS','IDBFS']"
    "-sEXPORTED_FUNCTIONS=['_malloc','_free']"
)
# Relink when worklet-clock.js changes, which CMake can't see by itself.
set_target_properties(acidulous PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/worklet-clock.js")
