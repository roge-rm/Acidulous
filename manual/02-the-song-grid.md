# The song grid
> The arranger, the launcher, and freezing.

The grid works in two ways. The square in its top left corner switches between
them.

## As an arranger

This is the default. The song plays scene by scene from left to right. Each
scene plays all its clips, repeats as many times as its header says, and then
the next scene starts.

- Tap a scene's header to play just that scene.
- Hold a scene's header for its menu: settings, insert, duplicate, delete, and
  move left or right.
- The loop button at the left of the bottom bar: **tap** it to choose what
  loops, the whole song or the current scene. **Hold** it to choose whether it
  loops at all: **⟳** loops forever, **⇥ end** plays through once and stops.
- Stop means stop. The next play starts from the top of the song.
- If something keeps sounding, **hold play** to silence everything: every
  note, echo and tail. **Panic** under About… in the file menu does the same.
- If Acidulous ever closes unexpectedly, it says so the next time it opens and
  offers to share a report. Reports stay on the phone unless you share one;
  the last one is also in About.

### A scene's tempo

In a scene's settings, **tempo** can follow the song or be the scene's own. An
own tempo either jumps in when the scene starts or glides in over its first
bar.

**Ramp at end** changes the tempo inside the scene: to the bpm set by **to**,
over the last bars set by **over**. Slow down into the next scene, or speed up
across a whole one. It happens on the scene's last time through, so a scene
that repeats four times only slows at the very end. The next scene then starts
at its own tempo. The header shows ↘ or ↗ on a scene with a ramp, and a MIDI
export writes it as a tempo change on every beat.

## Tracks

Tap a track's name for its menu: change machine, settings, rename, freeze,
duplicate and delete. Hold the name, or pick **Settings…**, for the track's
own settings:

- **name** and **colour**. A track's colour otherwise comes from where it sits.
- **transpose** - moves every note up or down as it plays, from the clip and
  from your fingers alike. The clip keeps what was written, so setting it back
  to 0 puts the part back. Not on drum machines, whose notes pick sounds.
- **tuning** - the song's, or the track's own. See
  [Tunings](05-effects-and-mixing.md).
- **velocity** - **as played**, or every note at one velocity.
- **swing** - the song's, or the track's own amount.
- **midi out** and **channel** - **off**, **both** (the machine and the
  hardware) or **only** (the hardware, with the machine silent).
- **output** - the master or one of the mixer's groups.

The last two are also on the track's mixer strip.

## As a launcher

Tap the corner square and the grid becomes a clip launcher. Each track plays its
own clip, from any scene.

- Tap a clip to launch it. It starts on the next beat line rather than
  straight away, so it lands in time.
- The **q:** button sets what it waits for. **end** waits for the playing clip
  to finish its loop. The others wait for a number of bars.
- Tap a scene's header to launch that whole column.
- Tap a playing clip to stop it at the end of its loop. Tap stop twice to stop
  everything.
- Each track keeps its own position, and stop leaves them where they are. The
  readout above the bottom bar shows where each one is.

### Looping

In the launcher, an empty cell is a looper.

- Tap an empty cell. It becomes a clip, starts on the next line, and records
  what you play into it. The cell's edge pulses red while it records.
- Tap it again to close the loop. It ends on the nearest bar line. If nobody
  taps, it closes itself at 16 bars.
- Once closed it goes on recording on top of itself each time round, with a
  steady red edge. Tap to stop adding to it, and tap again to add more.
- To have loops close themselves at a set length, pick one under **loops
  record for** in the **q:** window.

What you end up with is an ordinary clip. Double tap it to edit it.

## Zooming

Drag with two fingers to move around the grid, and pinch to make the cells
bigger or smaller. One finger still opens and launches clips.

## Clip settings

Hold a clip for its settings: its length in bars, mute, and the grid it snaps
to.

**Copy, cut, paste and clear** are at the top. You can hold any cell to get
them, including an empty one, so you can paste a copied clip into another scene
or another track. Paste replaces the whole clip, so pasting over a clip asks
first, and so does clear. Cut doesn't ask, because the clip is still on the
clipboard.

A copy includes the notes, automation and settings, including its length. It
doesn't include frozen audio, so a pasted clip plays its machine until you
freeze it again. Recordings on an audio track do come along.

## What a cell shows

The number of bars is in the corner, with **1** for a one-shot and **M** for a
muted clip. A cell on an audio track shows its waveform and how many of its four
lanes have a recording. It turns **amber** if one was recorded at a different
tempo from the scene's, because audio doesn't stretch there.

## Freezing

Freezing renders a clip to audio and plays that instead of the machine, which
frees up the CPU for everything else. You can freeze one clip, a whole scene or
a whole track from the menus.

The track's two insert effects are frozen in as well, so a frozen clip costs
almost nothing to play. The mixer stays live: fader, pan, sends and mute all
still work.

The tail (reverb, delay, long release) is rendered too and kept separately, so
it rings over the next loop and keeps ringing when the clip stops, the same as
the live machine would. The render keeps going until the sound has died away,
up to eight seconds.

If a scene ramps smoothly to a new tempo, frozen clips in it are time-stretched
through the ramp without changing pitch.

Changing the machine or either effect makes a freeze out of date, and so does
changing the tempo. The clip gets a mark when that happens. Thaw it or freeze it
again.

## When the phone can't keep up

If the engine starts falling behind while you play, **the tracks costing the
most glow red**, and so does the load meter in the header. Nothing stops.

A track only glows when the engine is running late right then *and* that track
is a big part of the load. Freezing it is usually the answer.

**Settings · audio** has the details: the worst block, where the time went, and
each track's cost.
