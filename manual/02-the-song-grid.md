# The song grid
> The arranger, the launcher, and freezing.

The grid does two jobs, and the square in its top left corner says which one it
is doing.

## As an arranger

This is the default. The song plays scene by scene, left to right: every track
plays its clip for that scene, the scene repeats as many times as its header
says, and then the next one starts. Press play and it plays the song.

- Tap a scene's header to hear that scene on its own.
- Hold a scene's header for its menu: settings, insert, duplicate, delete, and
  move it left or right.
- The loop pill at the left of the bottom bar loops the whole song or the scene
  that is playing.

## As a launcher

Tap the corner square and the same grid becomes a launcher: every track picks
its own clip, from any scene, and they need not be from the same one.

- Tap a clip to launch it. It starts on the next line rather than immediately,
  so it arrives in time with what is already playing.
- The **q:** pill sets what it waits for. **end** is the musical answer: the
  clip you are replacing finishes the cycle it is in. Anything else is a plain
  grid of bars.
- Tap a scene's header to launch that whole column at once.
- Tap a playing clip to stop it at the end of its cycle; tap the stop control
  twice to cut everything.

Every track keeps its own count in this mode, which is the whole point, and the
readout above the bottom bar is the only place you can read that as a number.

## Zooming the grid

Two fingers move the grid and change how much of it you can see - drag to move
around it, pinch to make the cells larger or smaller. One finger still launches
or opens a clip, so nothing is in the way of playing.

## Clip settings

Hold a clip for its settings: how many bars it is, whether it is muted, and the
grid it snaps to.

## What a cell tells you

The number of bars is in the corner, with **1** for a one-shot and **M** for a
muted clip. A cell on an audio track also shows its waveform and how many of
its four lanes hold a recording - and says so **in amber** when one of them was
recorded at a tempo this scene does not play at, since audio does not stretch.

## Freezing

Freezing renders a clip to audio and plays that instead of the machine, which
gives the processor back to everything else. Freeze one clip, a whole scene or a
whole track from the menus.

**The insert effects are frozen too.** What is rendered is the machine and both
of its slots, so a frozen track costs a read from memory and nothing else - that
is where the saving comes from. What stays live is the *mixer*: the fader, pan,
the two sends and mute all still work over a frozen clip, which is the line
between freezing and bouncing.

Because they are baked in, changing the machine or either effect makes the
freeze out of date, and the clip says so - the same mark it shows when the
tempo has moved. Thaw it or freeze it again.

**Which track to freeze** is in **Settings · audio**: the worst block, where its
time went, and the tracks that cost the most, worst first.
