# alsa-lib 1.2.14 headers, vendored

The desktop build's MIDI on Linux goes through ALSA's sequencer
(`platform/desktop/alsa_seq.cpp`). Only the headers are here: libasound itself
is opened at run time with `dlopen`, as miniaudio opens it for the audio, so the
engine builds on a machine without `libasound2-dev` - and in the Pi's cross
build - and nothing links against it. The phone build does not include them.

`include/alsa` is Debian Trixie's `libasound2-dev` 1.2.14-1+deb13u1 (amd64;
the headers are the same for every architecture), unpacked unmodified:

    apt-get download libasound2-dev
    sha256 dfa8e8a133ef8704093d03464d86a3e86d26232baf1e3827aa030cb68c437e1c  libasound2-dev_1.2.14-1+deb13u1_amd64.deb
    dpkg-deb -x libasound2-dev_*.deb x && cp -r x/usr/include/alsa include/

## Licence

GNU Lesser General Public License, version 2.1 or later (Debian's copyright
file for the package: `Files: * License: LGPL-2.1+`). What the engine takes
from the headers is declarations, constants and the event-setting macros,
which the LGPL's section 5 allows in a work that uses the library; the library
is loaded, never compiled in, so it stays replaceable. The text ships in the
app: `licences/LGPL-2.1.txt`.
