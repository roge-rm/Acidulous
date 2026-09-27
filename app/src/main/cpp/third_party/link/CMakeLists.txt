# Ableton Link, as headers. There is nothing to compile: the library is
# entirely in its headers, and the two definitions below are how it is told
# which platform it is on and that asio is standalone rather than Boost's.
#
# Unlike LAME next door, this one *is* compiled into libacidulous.so - it is
# under the GPL rather than the LGPL, so there is no relinking freedom to
# preserve, and a header-only library has no other way to be used.
cmake_minimum_required(VERSION 3.22.1)

add_library(ableton_link INTERFACE)

target_include_directories(ableton_link INTERFACE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../asio/include
)

if(WIN32)
    # The desktop's Windows build: Winsock, the IP helper for the interfaces'
    # addresses, and the multimedia timer Link's clock is steadied by.
    target_compile_definitions(ableton_link INTERFACE LINK_PLATFORM_WINDOWS=1 ASIO_STANDALONE _WIN32_WINNT=0x0A00)
    target_link_libraries(ableton_link INTERFACE ws2_32 iphlpapi winmm)
else()
    target_compile_definitions(ableton_link INTERFACE
        LINK_PLATFORM_LINUX=1 # what Android is, to Link
        ASIO_STANDALONE
    )
endif()
