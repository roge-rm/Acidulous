# Exporting
> Getting the song out.

**Export…** in the file menu renders the song faster than real time, through the
same engine that plays it, tails included.

You can export the whole song, the current scene, or stems.

## Formats

- **WAV** and **AIFF** - 16 or 24 bit, or 32-bit float.
- **FLAC** - lossless and smaller.
- **MP3** and **AAC** - at the bitrate you choose.
- **MIDI** - the notes, not the sound.
- **Song bundle** - the song and the samples it uses, in one file you can share.

## Stems

Stems writes each track to its own file in one pass, along with the full mix.
A track routed into a group is part of the group's stem instead of having its
own, so the stems add up to the mix.

## Loudness

**loudness** is either **as mixed** or **-14 LUFS**. With -14, the song is
rendered twice: once to measure it, then again with the gain that brings it to
-14 LUFS, which is about where streaming services play things. The gain is held
back if it would push the true peak over -1 dBTP, so a very dynamic song may end
up a little under -14. Stems get the same gain, so they still add up to the mix.

The master strip in the mixer shows the same measurement while you play:
integrated LUFS, and true peak underneath (red above -1 dBTP). It resets every
time you press play, or when you tap it.

## Exports are repeatable

Exporting the same song twice gives identical files, because every machine is
reset before the render starts.
