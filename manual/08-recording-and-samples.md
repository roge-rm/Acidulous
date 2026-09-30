# Recording and samples
## The recording window

One window handles recording, and it opens wherever a machine needs audio: a
pad on Forage, a loop for Dice, a buffer for Pollen or a take for Molt.

- **Record** - choose the input, watch the level and record.
  - **source** is **in** for the microphone or what's plugged in, or
    **resample** to record what the app is playing.
  - **mic** is **raw** for an instrument, or **clean** for a voice, with noise suppression and level control.
  - The **tuner** in the input card shows the nearest note and how many cents
    off you are, and turns green within four cents. It listens before the input
    effects, and shows nothing unless it's sure of the note.
  - **printed into the take** holds two effects that are recorded into the
    file, like a guitar amp. Effects on a track can be changed any time
    instead.
- **Edit** - play the take back, trim the ends, set the level and cut low
  rumble. **norm** and **rev** at the top normalise and reverse the whole file.
  Changes show and play straight away. **apply** writes them to the file, and
  **revert** takes them all back.
- **Library** - everything you've recorded or imported.
- **Voice** - records your voice for Diction, a short prompt at a time.
  - **new voice** starts one, and **delete** deletes the one chosen, with its
    takes. **share** sends it as a zip, its takes and all. **note** is the note every prompt is sung on;
    pick one that's easy for you, since it can't change after the first take.
  - Tap **sing** and listen to the note. It counts 3, 2, 1, then the prompt
    turns red: sing it on the note, in whatever octave suits your voice. The
    bar under it shows where each part goes. **ah-sah** is ah, then sah, in
    one breath, with the s where the bar marks it: it's the consonant going
    in and out of a vowel that's being recorded. A vowel that moves, like
    **eye**, holds its first vowel and moves to the second at the mark near
    the end: aaah-ee. It moves on to the next prompt by itself.
  - Each take is checked as soon as it's sung. One that has to be sung
    again stays on screen in pink and says why, such as **too loud** or **no
    consonant heard**. The counts only count takes that are fine.
  - **mic** shows the level. Sing loud enough to keep it well up the bar. A
    take always records the mic raw, without the effects printed into a take.
  - **◀** and **▶** go back and forward, **play** plays a take back, and
    **again** records it over.
  - **cut…** shows the take with the part it was cut to marked: the held
    vowel, a vowel's move, or the consonant. Drag a mark to move it, and
    **part** plays what the marks hold. **keep** saves it, and a take that
    said to sing it again is used as you marked it. **as cut** puts back where
    it was cut first.
  - **to sing** counts what a voice needs: every vowel, and every consonant
    between ahs. After it come the consonants **between ees** and **between
    oos**, which you can leave out. Diction's **from…** window chooses between
    them for each consonant. A voice is saved as you go, so you can stop and
    finish it another day.

The result is a file, so the same recording can be used by more than one
machine.

## Importing

You can import WAV, AIFF, FLAC and MP3. Everything is converted to WAV once on
the way in, so songs load quickly.

## Recording onto a track

**Bias** is the audio track. Open a Bias cell, tap the red dot next to a lane,
arm record on the transport and press play. The song plays while you record,
and the other lanes keep playing.

When you stop, the take is cut at the scene lines. One recording over the whole
song becomes one cell per scene, all pointing at the same file, with new cells
in scenes where the track was empty. The full recording also stays in the sound
library, so you can undo the split and place it by hand.

Trimming, fades, crossfades, flattening lanes and tempo following are on
**Bias**'s page. None of them change the file.

Takes can be up to half an hour. Anything over two minutes is converted once in
the background and streamed from storage, so there may be a short wait the first
time a long take is used.

Only one lane records at a time, and arming a second lane disarms the first.

If the recorder fell behind and left a gap in the take, it isn't split, and the
whole take is kept in the library. If the transport never played, nothing was
recorded against a scene, and the app tells you.

## Machines that use audio

- **Forage** - one sample per pad, with a filter, crusher and pitch envelope.
- **Mosaic** - zones across the keyboard and velocity, from your own files or a
  SoundFont.
- **Pollen** - grains from a file or the live input.
- **Dice** - a loop cut into slices.
- **Molt** - a sung take, tuned by the piano roll.
- **Cipher** and **Filament** can use the live input.
- **Bias** - four lanes of recordings along the song. Its recordings belong to
  its cells instead of the machine.
