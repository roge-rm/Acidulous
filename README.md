# Acidulous

A music studio for Android: sixteen racks of synthesizers and drum machines, a
scene-based arranger, and an engine written for this project from the ground
up.

It takes the *shape* of Caustic 3 — a rack you fill with machines, each one a
synth and a sequencer track at once — and builds every instrument in it from
scratch. Nothing here is a clone and nothing here is sampled from anywhere
else; the machines are original designs that happen to stand in the same
places.

**Status: in development.** It runs, it makes sound, it saves songs and it
exports them. It has not been released.

Discussion, issues and suggestions: **[Discord](https://discord.gg/9Wun47jGC6)**.

---

## What is in it

### Nineteen machines

| | |
|---|---|
| **Subvert** | The signature bass mono. Accent is velocity and slide is legato, so a line is played rather than programmed. |
| **Trinity** | Three-oscillator poly with wavetables, density, FM and drift. |
| **Ratio** | Six-operator FM with morphing algorithms and ratios you can snap or skew. |
| **Manual** | An organ: two manuals and pedals over one shared 91-wheel generator, four models, rotary cabinet. |
| **Cumulus** | A pad machine that builds its spectrum offline and plays it back — bandwidth, not oscillators. |
| **Formulate** | An 8-bit machine with tracker tables and a small expression language, so a waveform is something you write. |
| **Filament** | Modelled strings: the loop is solved from its own phase, and the strings hear each other. |
| **Brazen** | A brass model — lips blown open against a tube, and a lip Q with a floor. |
| **Timber** | A woodwind model: the reed's table is a curve and the tube sits below the note. |
| **Resonance** | Modal percussion. Six shapes' worth of mode ratios, struck somewhere with something, and the pads ring into each other. |
| **Hexbeat** | A drum synthesizer in the small-box vocabulary, expanded to the kit those boxes never had. Thirteen voices, nothing sampled. |
| **Genesis** | The big drum box: circuit drift, and a bus compressor with the kick wired to its side chain. |
| **Mosaic** | A multisample player — zones, key and velocity crossfades, a layer scan, and a grain engine over the map. |
| **Pollen** | Granular: one view over a mounted file and a live ring. |
| **Dice** | A slicer. A loop in pieces, with a probability on every trigger. |
| **Forage** | The sample drum machine — thirteen pads, your own files. |
| **Cipher** | A vocoder. The band map between analysis and synthesis is the instrument. |
| **Molt** | A voice you write for: a sung take turned into an instrument, with pitch and formant moved independently. |
| **Nexus** | A modular, whose blocks are this app's own instruments and whose patch is text. |

### Nine insert effects

Bitcrusher, Compressor, Delay, Distortion, EQ, Filter, Flanger, Phaser,
Reverb — two slots per rack, each with one classic behaviour and one extra.

### Sequencing

- **Scene-major arranger.** Clips vary in length within a scene; scenes repeat.
- **Clip launcher** as a second view of the same song, with per-track origins.
- Piano roll and drum grid, both views over the same clip.
- **Eventors** — Scale, Chord and Arp — as per-rack processors rather than
  edits, so the notes underneath stay as you played them.
- Automation lanes, performance lanes (mod and pressure), and per-note
  expression.
- Clip freeze: render a clip to audio, pre-fader.

### Playing and syncing

- MIDI in over USB and Bluetooth LE; MIDI out with clock.
- **MPE** — per-note pitch, pressure and slide.
- **Ableton Link**, for a tempo and a bar line shared with other machines on
  the network; MIDI clock in and out for everything else.
- Controller mapping: any CC or note onto any control.

### Getting sound out

Export to WAV, AIFF, FLAC or MP3, whole song or per-track stems in one pass,
with our own writers for everything but MP3.

---

## Building

You need the Android SDK and NDK. The NDK version is pinned in
`app/build.gradle.kts` so the native ABI does not shift between machines.

```sh
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM unit tests
```

| | |
|---|---|
| Minimum Android | 8.1 (API 27) |
| Built against | API 37 |
| ABIs | `arm64-v8a`, `x86_64` — 64-bit only |
| UI | Kotlin, Jetpack Compose, phone-first |
| Engine | C++17, `app/src/main/cpp`, Android audio via Oboe |

## Testing

The engine's harnesses run on the host, not on a device, and take a few
seconds together:

```sh
tools/all_tests.sh
```

They cover the sequencer's launcher and song position, MIDI clock in and out,
the metronome, MPE, Ableton Link's arithmetic (and, where the network allows
it, two real Link peers in one process), the audio file writers, Molt's
analysis, the expression language, and a reset-determinism pass that plays
every machine, panics it, plays the same performance again and requires the
two renders to match bit for bit.

`tools/bank_test.sh` checks the factory presets: that every patch sounds,
that none of them are silent or clipped, and that no two siblings in a bank
are the same sound twice.

### The audition harness

`tools/audition.sh` plays a factory patch on a desk, writes a wav and prints
what it measures — loudness, peak, brightness, how long it takes to speak,
how long it rings. It is how the preset banks are voiced: the harness says
what a patch measures, and a person says what it sounds like. Neither is
enough on its own.

```sh
tools/audition.sh bank Trinity            # every patch, and the spread
tools/audition.sh play Subvert Acid       # one patch
tools/audition.sh params Mosaic           # the parameter table
```

---

## Talk to us

Questions, bug reports, feature ideas and works in progress all go to the
Discord: **<https://discord.gg/9Wun47jGC6>**.

---

## Licence

Acidulous is free software under the **GNU General Public License, version 3
or later**. See [LICENSE](LICENSE).

Copyright © 2026 Dan Hunke.

It is GPLv3-*or-later* rather than GPLv2 for a specific reason: Oboe is
Apache 2.0, which is compatible with GPLv3 and not with GPLv2.

Third-party components — Oboe, LAME, Ableton Link and asio — are listed with
their licences and the reasoning in [NOTICE](NOTICE). Every licence text is
shipped inside the app and can be read from its About window.

**Everything else in this repository is original work.** The engine, every
machine and effect, the sequencer, the WAV, AIFF and FLAC writers and the MIDI
file writer were all written for this project. No DSP, no presets and no
samples are taken from anywhere else.
