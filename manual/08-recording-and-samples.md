# Recording and samples
> Getting audio in, and what to do with it.

## The recording window

One window handles recording, and it opens wherever a machine needs audio: a
pad on Forage, a loop for Dice, a buffer for Pollen, or a take for Molt.

- **Record** - choose the input, watch the level, and record.
  - The **tuner** at the top shows the nearest note and how many cents off you
    are, and turns green within four cents. It listens before the input effects,
    and shows nothing unless it's sure of the note.
  - **printed into the recording** holds two effects that are recorded into the
    file, e.g. a guitar amp. Effects on a *track* can be changed any time
    instead.
- **Edit** - trim the ends, set the level, and cut low rumble.
- **Library** - everything you've recorded or imported.

The result is a file, so the same recording can be used by more than one
machine.

## Importing

You can import WAV, AIFF, FLAC and MP3. Everything is converted to WAV once on
the way in, so songs load quickly.

## Recording onto a track

**Bias** is the audio track. Open a Bias cell, tap the red dot next to a lane,
arm record on the transport, and press play. The song plays while you record,
and the other lanes keep playing.

**When you stop, the take is cut at the scene lines.** One recording over the
whole song becomes one cell per scene, all pointing at the same file, and cells
are created in scenes where the track was empty. The full recording also stays
in the sound library, so you can undo the split and place it by hand.

Trimming, fades, crossfades, flattening lanes, and tempo following are covered
on **Bias**'s page. None of them change the file.

Takes can be up to half an hour. Anything over two minutes is converted once in
the background and streamed from storage, so there may be a short wait the first
time a long take is used.

Only one lane records at a time. Arming a second lane disarms the first.

If the recorder fell behind and there's a gap in the take, it isn't split, and
the whole take is kept in the library. If the transport never played, nothing
was recorded against a scene, and the app tells you.

## Machines that use audio

- **Forage** - one sample per pad, with a filter, crusher and pitch envelope.
- **Mosaic** - zones across the keyboard and velocity, from your own files or a
  SoundFont.
- **Pollen** - grains from a file or the live input.
- **Dice** - a loop cut into slices.
- **Molt** - a sung take, tuned by the piano roll.
- **Cipher** and **Filament** can use the live input.
- **Bias** - four lanes of recordings along the song. Its recordings belong to
  its cells rather than to the machine.
