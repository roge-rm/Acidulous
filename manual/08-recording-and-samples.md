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

## Recording onto a track

**Bias** is the four-track, and recording onto it is how a song gets a voice on
it. Open a Bias cell, tap the red dot beside the lane you want, arm the
transport's record button, and press play: what you sing is written while the
song plays under you, and the other lanes keep playing, which is what
overdubbing on a four-track is.

**The take is cut at the scene lines when you disarm.** One recording sung over
a whole song becomes one cell per scene, each a window into the same file - so
nothing is copied, and every scene you crossed now holds the part of the take
that belongs to it. Scenes the track had nothing in are made as they are
reached.

The whole recording stays in the sound library under its own name, so a split
you did not want can be thrown away and the audio placed by hand instead.

**A take may be half an hour long.** Anything under two minutes is held in
memory as it always was; past that it is converted once, in the background, and
read from storage as it plays - so a vocal that runs the length of a song costs
no more memory than a chorus does. You will not notice either happening, except
that the first time a long recording is used there is a moment while it is
converted.

One lane records at a time, because there is one recorder. Arming a second lane
lets the first one go.

Two things it will tell you rather than guess at. If the recorder fell behind
and the take has a gap in it, it is **not** split - every moment after the gap
would be in the wrong place - and it is left in the library whole. And if the
transport never played, nothing was recorded against a scene and it says so.

## The machines that take audio

- **Forage** - a pad each, with a filter, a crusher and a pitch envelope.
- **Mosaic** - a map of zones across the keyboard and across velocity, from your
  own files or a SoundFont.
- **Pollen** - grains over the file, or over what is coming in live.
- **Dice** - the loop cut into slices you can roll.
- **Molt** - a sung take, retuned by the piano roll.
- **Cipher** and **Filament** can both take the live input as their source.
- **Bias** - four lanes of recordings, along the length of the song. Unlike the
  rest of these, its material belongs to the cells rather than to the machine;
  see [The machines](04-the-machines.md).
