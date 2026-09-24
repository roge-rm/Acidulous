# Importing and exporting
> Getting songs and sounds in, and the song out.

**Export…** in the file menu renders the song faster than real time, through the
same engine that plays it, tails included.

You can export the whole song, the current scene, or stems.

## Formats

- **WAV** and **AIFF** - 16 or 24 bit, or 32-bit float.
- **FLAC** - lossless and smaller.
- **MP3** and **AAC** - at the bitrate you choose.
- **MIDI** - the notes, not the sound. Drum tracks go on channel 10 as General
  MIDI drums, so other programs hear the right sounds.
- **Song bundle** - the song and the samples it uses, in one file you can share.

## Stems

Stems writes each track to its own file in one pass, along with the full mix.
Each mixer group is a stem too, and a track routed into a group is part of the
group's stem instead of having its own, so the stems add up to the mix.

## Loudness

**loudness** is either **as mixed** or **-14 LUFS**. With -14, the song is
rendered twice: once to measure it, then again with the gain that brings it to
-14 LUFS, which is about where streaming services play things. The gain is held
back if it would push the true peak over -1 dBTP, so a very dynamic song may end
up a little under -14. Stems get the same gain, so they still add up to the mix.

The master strip in the mixer shows the same measurement while you play:
integrated LUFS, and under it the short-term reading (**S**) and the true peak
(**TP**). TP turns red above -1 dBTP. It resets every time you press play, or
when you tap it.

## Exports are repeatable

Exporting the same song twice gives identical files, because every machine is
reset before the render starts.

## Importing

**Import…** in the file menu takes a file from anywhere on the phone. What
happens depends on what it is:

- **A MIDI file** opens a window that shows each part in the file with a
  machine chosen for it. Tap a machine to change it, or pick **skip** to leave
  the part out. Drums on channel 10 go to Hexbeat, with each drum moved to the
  matching sound. Parts that say what instrument they are get a fitting
  machine: an organ goes to Manual, brass to Brazen.
- The file is cut into scenes of 4, 8 or 16 bars. A stretch that's the same as
  the one before becomes a repeat, so a loop comes in as one scene played
  several times. The tempo and time signature come from the file.
- It opens as a new song and is saved straight away.
- **A song bundle** (a .zip made by Export) opens as a song, with its samples.
  If you already have a sample with the same name, yours is kept and the
  bundle's comes in under a new name.
- **A sound** (WAV, AIFF, FLAC or MP3) goes into the sound library, up to ten
  minutes of it.

An imported song never replaces one you've saved: if the name is taken, it gets
a number after it.

