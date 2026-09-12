# LAME 3.100, vendored

MP3 is the one format everybody asks for and the only one Android cannot
make: the platform ships no MP3 *encoder*. The patents expired in 2017, so
what was left was a licence question, and LAME's answer suits us.

    https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
    sha256 ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e

## What is here, and what is not

Only what is needed to *encode*: `include/lame.h` and `libmp3lame/`, with the
project's own `COPYING`, `LICENSE` and `README`. Left behind: the command
line frontend, the decoder tests, the DirectShow and ACM wrappers, the Mac and
DOS ports, the autotools machinery, and the hand-written i386 assembly
(`libmp3lame/i386`) - none of which an Android build uses. `libmp3lame/vector`
is here because `fft.c` includes its header whether or not the SSE paths are
built; its one source file is not in our `CMakeLists.txt`. The tarball above
is the whole of it if you want the rest.

`config.h` here is ours: upstream generates one with autoconf, which does not
run in an NDK build, so it is written out instead. It asks the compiler for
the type sizes rather than stating them, because they differ between the
32-bit and 64-bit ABIs the app ships.

## Licence

LAME is under the **GNU Library General Public License, version 2** - see
`COPYING`. That is compatible with this app's GPLv3-or-later: LGPLv2 §3 says
a copy may be taken under the ordinary GPL, "any later version" included.

Two things follow, and both are done:

- **It stays its own shared library.** `libmp3lame.so` is built separately and
  loaded beside `libacidulous.so`, never linked into it, so anyone may replace
  it with their own build. That is the relinking freedom the LGPL is for.
- **It is acknowledged.** Their `LICENSE` asks that use of LAME be stated;
  the export window says so where the format is chosen.
