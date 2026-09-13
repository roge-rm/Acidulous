# Ableton Link 4.0, vendored

Playing in time with another machine on the same Wi-Fi: a shared tempo, a
shared beat, and a shared idea of where the bar starts. MIDI clock does this
down a cable and only in one direction; Link does it over the network,
between peers, with no master.

    https://github.com/Ableton/link  tag Link-4.0
    commit e9a2e414d63f55f1aad158370b007a6fbdc1eeb9

Re-take it with `tools/vendor_link.sh`, which is also the exact record of
what was copied.

## What is here, and what is not

Link is header-only, so `include/ableton` is the whole library. Left behind:
its tests, the **Link Audio** extension (streaming audio between peers - a
different feature we do not use), and the platforms this app does not build
for (`darwin`, `esp32`, `windows`). What stays is `platforms/linux`, which is
what Android is from Link's point of view, plus `posix`, `asio` and `stl`.

Nothing is edited. `CMakeLists.txt` here is ours: an interface target that
sets the include paths and the two definitions the library expects
(`LINK_PLATFORM_LINUX=1` and `ASIO_STANDALONE`).

Link needs **asio**, which comes with it as a submodule and is vendored
beside this - see `../asio/PROVENANCE.md`.

## Licence

Link is **dual-licensed**: the GNU General Public License **version 2 or
later**, or a proprietary licence bought from Ableton. This app takes the
first, which its GPLv3-or-later is free to do - GPLv2-*or-later* may be used
under version 3. See `LICENSE.md` and `GNU-GPL-v2.0.md`.

Taking the GPL option is a promise about *this* app, not only about Link:
because Link's headers are compiled into `libacidulous.so`, the whole of it
is distributed under the GPL. That was already true and is why the project
went GPLv3-or-later on 2026-09-12.
