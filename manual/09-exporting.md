# Exporting
> Getting the song out.

**Export…** in the file menu renders the song faster than real time, through the
same engine that plays it - so what you export is what you heard, including the
tail after the last note.

## Formats

- **WAV** and **AIFF** - uncompressed, at 16 or 24 bits.
- **FLAC** - the same samples, smaller.
- **MP3** - at the bitrate you choose.

The depth row becomes a rate row for the lossy formats, because a bitrate is
what that question means there.

## Stems

Export stems and every track is written as its own file, in one pass, with the
mix left alone. That is a mixing session somewhere else, without re-recording
anything. A track routed into a group is part of the group's stem rather than
getting one of its own, so the stems add up to the mix.

## Loudness

**loudness** in the export window is either **as mixed** or **-14 LUFS**. At
-14 the song is rendered twice: once to measure it, then again with the gain
that brings it to -14 LUFS integrated - the level most streaming services play
at. The gain is held back if it would push the true peak over -1 dBTP, so a
very dynamic song may come out a little under -14. Stems get the same gain as
the mix, so they still add up to it.

The master strip shows the same measurement while you play: integrated LUFS on
top, true peak under it, in red over -1 dBTP.
It starts again every time you press play; tap it to start it again yourself.

## Two renders of the same song match

Exporting the same song twice gives two identical files, because every machine
is put back to where it started before the render begins. If you are comparing
two exports and they differ, something in between changed.
