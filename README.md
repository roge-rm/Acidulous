# Acidulous

Acidulous is a music studio for Android 8.1 and up. 

Combine up to 16 synthesizers, drum machines, noise generators and processors together
into scenes of music and play them in order or pick and choose to generate something new every time.

In many ways this is an homage to the wonderful musical creation tool Caustic, many of the machines
here are heavily inspired by Caustic and I have had it in mind through this whole process. I have also
been inspired by many other synths, drum machines, and sequencers but I have tried to put my own stamp
on every inspiration and bring some of my own.

Read below for more details on the machines, sequencer, and app capabilities. 

You can very much make music with this now, exporting via a number of formats in full form or split into stems.
Please do and then let me know what works, what doesn't work, what could work in the future so I can grow it into something even more interesting.

Please join me in the #acidulous channel **[on my discord](https://discord.gg/9Wun47jGC6)**  to share comments, ask questions, report bugs or issues with different devices, or to request new features. Or feel free to open an issue here.

The manual is in [manual/](manual/) and in the app in the **Help…** window.

Enjoy,<br>
Dan (rm)

---

## What is in it

### Twenty machines

| | |
|---|---|
| **Reflux** | The signature bass mono. Accent is velocity and slide is legato, so a line is played rather than programmed. |
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
| **Bias** | A four-track that runs along the song. Four lanes of recordings per cell, sounding together; record over the song and the take is cut at the scene lines. |

### Sixteen effects

Each with one classic behaviour and one extra, and a page of its own in the
manual.

| | |
|---|---|
| **Delay** | Echoes on a note value, with a duck that gets out of the way while you are playing. |
| **Reverb** | A room, and four things a room cannot do: freeze it, gate it, send it up an octave, or make it out of eight-bit memory. |
| **Eq** | Three bands, and a tilt that trades top for bottom on one knob. |
| **Filter** | Low, band or high pass, swept by an LFO on a note value or by the signal's own envelope. |
| **Width** | The stereo image: wider, narrower, mono below a frequency, or turned. |
| **Distortion** | Four clipping characters, and a bias that makes the two halves of the wave behave differently. |
| **Amp** | A guitar amplifier as a chain — preamp, an interactive tone stack, a power stage that sags — with a **modelled** cabinet you can resize continuously between a combo and a stack. |
| **Bitcrusher** | Fewer bits and a lower rate, with a jitter that makes the clock unsteady. |
| **Compressor** | The classic four, and a pump in time with the transport that needs no sidechain routing. |
| **Gate** | Shut below a level, with a filter on its own detector so it opens for a pick and not for a room. |
| **Chorus** | Two to four detuned voices, and a drift that stops them agreeing about the tuning. |
| **Flanger** | One short sweeping delay, and inverted feedback for the hollow version. |
| **Phaser** | Allpass notches sweeping, from two stages to eight. |
| **Tremolo** | Amplitude on an LFO, and the same lever turned into an auto-pan. |
| **Shifter** | Frequency shifting — everything moves by the same number of hertz rather than the same interval, so a harmonic sound stops being one. |
| **Harmonizer** | Two added voices at scale degrees, so the harmony stays in key. |

Two insert slots per rack, and **the song's two send buses hold any of the same
sixteen**, chosen and edited from the master strip.

### Sequencing

- **Scene-major arranger.** Clips vary in length within a scene; scenes repeat.
- **Clip launcher** as a second view of the same song, with per-track origins.
- Piano roll and drum grid, both views over the same clip.
- **Modifiers** — Scale, Chord and Arp — between what you play and what is
  written down, so a clip holds the arpeggio rather than the key that made it,
  and always plays exactly what the roll shows.
- **Swing** as a time warp rather than a delay, per song with a per-track
  override, and the inverse applied on the way in so a part played against a
  swung song is stored straight.
- Automation lanes, performance lanes (mod and pressure), and per-note
  expression.
- Clip freeze: render a clip to audio, pre-fader, with its ring-out kept after
  the clip so a frozen track goes on sounding where a live one would.
- **Two effect slots on the input**, at the top of the recording window: they
  run before the recorder does, so an amp is printed into the take rather than
  only applied over it afterwards.
- **Audio tracks**, on the same sixteen racks: record over the song and the
  take is split into cells, with no second timeline in either view.

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

They cover the sequencer's launcher and song position, where a recording's
boundaries fall when it is cut into cells, reading a long file a piece at a
time, MIDI clock in and out,
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
tools/audition.sh play Reflux Acid        # one patch
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
