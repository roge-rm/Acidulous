# The editor
> Drawing notes, and what one note decides.

Opening a clip gives you the grid at the top, the machine's own controls in the
middle, and the instrument at the bottom.

## The grid

For a melodic machine it is a piano roll: pitch up the side, time across.

- Tap an empty square to put a note there; tap a note to take it away.
- Drag a note to move it, or drag its right-hand edge to make it longer.
- **Two fingers move the view**: drag to scroll through pitch and time, pinch to
  zoom. One finger always draws, which is what keeps the two apart.
- The **scl** corner cycles how the scale is shown: every note, the scale's notes
  lit, or only the scale's notes.

For a drum machine it is a grid of steps instead, one row per voice, and a tap
puts a hit in.

## Playing it in

Press record and play. What you play is written into the clip, quantised to the
clip's grid. The count-in, if you have set one, is in the settings.

The keyboard reads **where** you hit a key: low on the key is soft, high is hard.
The pill in the bottom bar switches that off, so every note comes out at full
strength - which is what you want when you are tapping a part in rather than
playing it. The drum pads work the same way and have their own setting.

Drag the performance row - the strip of controls just above the keys - up or
down to make the instrument taller or shorter.

## What a note can decide

Below the grid is a lane, folded away until you want it. Open it and pick what
it is showing; then drag inside it to set that property on each note.

- **note volume** - how hard that note is played.
- **probability** - how often it plays at all. A note at 50 plays half the time.
- **trig condition** - when it is allowed to play. `1:4` plays on the first of
  every four passes; `pre` plays only if the last conditional note played, `!pr`
  only if it did not; `fil` plays only while fill is held, `!fi` only while it is
  not.
- **ratchet** - how many times it retriggers within its own step.
- **micro timing** - how far off the grid it sits, up to half a sixteenth either
  way.

The same seed drives every one of these, so a clip that varies still plays the
same bar twice when you want it to.

## The controls

The machine's own panel sits between the grid and the instrument, and every
insert, modifier and send has the same one in a window of its own.

**Hold a knob to put it back.** It returns to the value it had when you opened
that panel - not to a factory setting, but to how it sounded when you got here.
Reopening the panel takes a new reading, so "when you got here" is always the
last time you came in. Faders and sliders do it too, the mixer's included.

While **MIDI mapping** is on, holding a control means something else: it clears
whatever is driving that control. The two never overlap, because mapping mode
takes every touch before the control sees it.

## Automation

The strip under the note lane records and draws a parameter over time. Move any
knob while recording and the move is written there; afterwards, pick the
parameter and draw it by hand.

## Folding

The lanes, the machine panel and the instrument each fold away with the small
chevron at their edge, and the grid takes the height. On a phone that is the
difference between four rows of pitch and fourteen.
