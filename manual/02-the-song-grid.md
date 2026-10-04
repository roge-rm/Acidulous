# The song grid
The grid can be used as an arranger or as a clip launcher, switch between
them by pressing the button in the top left corner of the song grid.

## As an arranger

This is the default. The song plays scene by scene from left to right. Each
scene plays all its clips, repeats as many times as its header says, and then
the next scene starts.

- Tap a scene's header to start there. With **⟳** that scene repeats, and with
  **⇥ end** the song plays on from it to the end and stops.
- Hold a scene's header for its menu: settings, insert, duplicate, delete and
  move left or right.
- Tap the loop button at the left of the bottom bar to loop the whole song or
  just the current scene. Hold it to choose **⟳** to loop forever or **⇥ end**
  to play through once and stop.
- After a stop, play starts again from the top of the song.
- The readout above the bottom bar shows the playing scene, which of its
  repeats it's on (**×1/2** is the first of two) and the bar and beat. The
  time on its right shows where you are in the song and how long it is. Tap
  it to show the time left instead, or the time since you pressed play, and
  tap again to go back.
- If something keeps sounding, hold play to silence every note, echo and
  tail. **Panic** in About… does the same.
- Playing stops by itself when a call comes in, another app starts playing or
  headphones are unplugged.
- If Acidulous ever closes unexpectedly, it says so next time it opens and
  offers to share a report. Reports stay on the phone unless you share one,
  and the last one is also in About.

### A scene's tempo

In a scene's settings, **tempo** can follow the song or be the scene's own,
either jumping in when the scene starts or gliding in over its first bar.

**Ramp at end** changes the tempo to the bpm set by **to** over the last bars
set by **over**, so you can slow down into the next scene or speed up across a
whole one. It happens on the scene's last time through, and the next scene
starts at its own tempo. A scene with a ramp shows ↘ or ↗ in its header, and a
MIDI export writes it as a tempo change on every beat.

## Tracks

Tap a track's name for its menu: change machine, settings, rename, freeze,
duplicate and delete. Hold the name, or pick **Settings…**, for the track's
settings:

- **name** and **colour**. Otherwise a track's colour comes from where it sits.
- **transpose** - moves every note up or down as it plays, from the clip and
  from your fingers. The clip keeps what was written, so setting it back to 0
  puts the part back. Not on drum machines.
- **tuning** - the song's, or the track's own. See
  [Tunings](05-effects-and-mixing.md).
- **velocity** - **as played**, or every note at one velocity.
- **swing** - the song's, or the track's own amount.
- **midi out** and **channel** - **off**, **both** (the machine and the
  hardware) or **only** (just the hardware).
- **output** - the master or one of the mixer's groups.

The last two are also on the track's mixer strip.

## As a launcher

Tap the corner button and the grid becomes a clip launcher, where each track
plays its own clip from any scene.

- Tap a clip to launch it. It starts on the next beat line so it lands in
  time.
- The **q:** button sets what it waits for. **end** waits for the playing clip
  to finish its loop, and the others wait for a number of bars.
- Tap a scene's header to move to that scene. Its clips start and every other
  track stops, all on the same line. A clip that's already playing carries on.
- Tap a playing clip to stop it at the end of its loop. Tap stop twice to stop
  everything.
- Each track keeps its own position, and stop leaves them where they are. The
  readout above the bottom bar shows where each one is, and on the right the
  time since you pressed play.

### Looping

In the launcher, an empty cell is a looper.

- Tap an empty cell. It becomes a clip, starts on the next line and records
  what you play into it. Its edge pulses red while it records.
- Tap it again to close the loop on the nearest bar line. If you don't, it
  closes itself at 16 bars.
- Once closed it keeps recording on top of itself each time round, with a
  steady red edge. Tap to stop adding to it, and tap again to add more.
- To have loops close at a set length, pick one under **loops record for** in
  the **q:** window.

What you end up with is a normal clip. Double tap it to edit it.

## Zooming

Drag with two fingers to move around the grid and pinch to make the cells
bigger or smaller. One finger still opens and launches clips.

On a tablet or a big window the cells grow to fill the screen, up to three
times their size, until you pinch.

## Clip settings

Hold a clip for its settings: its length in bars, mute and the grid it snaps
to.

**Copy, cut, paste and clear** are at the top. You can hold any cell for them,
even an empty one, so you can paste a clip into another scene or track. Paste
and clear ask first when there's a clip there. Cut doesn't, since the clip is
still on the clipboard.

A copy has the notes, automation and settings, including its length. It
doesn't have frozen audio, so a pasted clip plays its machine until you freeze
it again. Recordings on an audio track do come along.

## What a cell shows

The number of bars is in the corner, with **1** for a one-shot and **M** for a
muted clip. A cell on an audio track shows its waveform and how many of its four
lanes have a recording. It turns **amber** if one was recorded at a different
tempo from the scene's.

## Freezing

Freezing renders a clip to audio and plays that instead of the machine, which
frees up CPU for everything else. You can freeze one clip, a whole scene or a
whole track from the menus.

The track's two insert effects are frozen in too, so a frozen clip costs almost
nothing to play. The mixer still works: fader, pan, sends and mute.

The tail (reverb, delay, long release) is rendered too, up to eight seconds, so
it rings over the next loop and after the clip stops like the live machine
would.

If a scene ramps to a new tempo, frozen clips in it are time-stretched through
the ramp without changing pitch.

Changing the machine, either effect or the tempo makes a freeze out of date,
and the clip gets a mark. Thaw it or freeze it again.

## When the phone can't keep up

If the engine starts falling behind, the tracks costing the most glow red, and
so does the load meter in the header. Nothing stops.

A track only glows when the engine is running late and that track is a big
part of the load. Freezing it usually fixes it.

With **diagnostics** on, **Settings › audio** has the details: the worst block,
where the time went and each track's cost.
