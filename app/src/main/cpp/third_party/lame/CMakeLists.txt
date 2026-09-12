# LAME, as its own shared library and never part of ours.
#
# The LGPL's point is that somebody can replace this with their own build of
# it, which means it must stay a separate .so that libacidulous loads beside
# itself rather than object code compiled into it. See PROVENANCE.md.
cmake_minimum_required(VERSION 3.22.1)
project(mp3lame LANGUAGES C)

add_library(mp3lame SHARED
    libmp3lame/VbrTag.c
    libmp3lame/bitstream.c
    libmp3lame/encoder.c
    libmp3lame/fft.c
    libmp3lame/gain_analysis.c
    libmp3lame/id3tag.c
    libmp3lame/lame.c
    libmp3lame/mpglib_interface.c
    libmp3lame/newmdct.c
    libmp3lame/presets.c
    libmp3lame/psymodel.c
    libmp3lame/quantize.c
    libmp3lame/quantize_pvt.c
    libmp3lame/reservoir.c
    libmp3lame/set_get.c
    libmp3lame/tables.c
    libmp3lame/takehiro.c
    libmp3lame/util.c
    libmp3lame/vbrquantize.c
    libmp3lame/version.c
)

target_include_directories(mp3lame
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}
            ${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame
)

# HAVE_CONFIG_H points it at the config.h above rather than at configMS.h.
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)

# Somebody else's C, warned about on their terms rather than ours: it is
# vendored unmodified and the warnings are not ours to fix.
target_compile_options(mp3lame PRIVATE -O3 -w)

find_library(log-lib log)
target_link_libraries(mp3lame ${log-lib})
