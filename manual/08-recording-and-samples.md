# Recording and samples
> Getting audio in, and what to do with it.

## The recording window

One window does all of it, and it opens wherever audio is wanted - a pad on the
sample machine, a loop for the slicer, a buffer for the granular, a take to
sing into the voice machine.

- **Record** - choose which input, watch the level, and capture.
- **Edit** - trim the ends, set the level, and take the rumble off the bottom
  with a low cut.
- **Library** - everything you have recorded or imported, to use again.

What it hands back is a file, so the same recording can be used by more than one
machine.

## Importing

Bring in WAV, AIFF, FLAC and MP3. Whatever the format, it is decoded once on the
way in and stored as a WAV, so nothing downstream has to know about formats and
a song load is never a decode.

## The machines that take audio

- **Forage** - a pad each, with a filter, a crusher and a pitch envelope.
- **Mosaic** - a map of zones across the keyboard and across velocity, from your
  own files or a SoundFont.
- **Pollen** - grains over the file, or over what is coming in live.
- **Dice** - the loop cut into slices you can roll.
- **Molt** - a sung take, retuned by the piano roll.
- **Cipher** and **Filament** can both take the live input as their source.
