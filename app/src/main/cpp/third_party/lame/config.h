/*
 * LAME's build configuration, written out rather than generated.
 *
 * Upstream produces this with autoconf, which does not run inside an NDK
 * build, so this says the same things for Android. The type sizes are asked
 * of the compiler rather than stated: `long` is four bytes on the 32-bit ABIs
 * and eight on the 64-bit ones, and getting that wrong miscompiles the
 * bitstream writer rather than failing to build.
 */
#ifndef ACIDULOUS_LAME_CONFIG_H
#define ACIDULOUS_LAME_CONFIG_H

#define PACKAGE "lame"
#define PACKAGE_NAME "lame"
#define VERSION "3.100"

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1

#define HAVE_MEMCPY 1
#define HAVE_STRCHR 1

#define SIZEOF_SHORT __SIZEOF_SHORT__
#define SIZEOF_INT __SIZEOF_INT__
#define SIZEOF_LONG __SIZEOF_LONG__
#define SIZEOF_LONG_LONG __SIZEOF_LONG_LONG__
#define SIZEOF_FLOAT __SIZEOF_FLOAT__
#define SIZEOF_DOUBLE __SIZEOF_DOUBLE__

/* float is IEEE 754 single on every ABI Android has. */
#define HAVE_IEEE754_FLOAT32 1
#define ieee754_float32_t float

/*
 * The decoder. `mpglib/` is vendored now, so `hip_decode` has something
 * behind it: the app writes four audio formats and reading mp3 back was the
 * one that needed somebody else's code. `mpglib_interface.c` was already in
 * the build, gated on this and doing nothing.
 */
#define HAVE_MPGLIB 1

/*
 * Not defined, deliberately:
 *   HAVE_NASM        - hand-written i386 assembly, not vendored
 *   HAVE_XMMINTRIN_H - the SSE paths, likewise
 *   HAVE_ANALYSIS    - the analysis hooks the GTK frontend uses
 *   DECODE_ON_THE_FLY - the encoder checking its own output as it goes,
 *                       which doubles the work for a number nobody reads
 */

#endif
