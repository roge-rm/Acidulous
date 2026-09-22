# The editor
> Drawing notes, and what each note can do.

A clip opens with the note grid at the top, the machine's controls in the
middle, and the keyboard or pads at the bottom.

## The grid

For a melodic machine it's a piano roll: pitch up the side, time across.

- Tap an empty square to add a note, tap a note to delete it.
- Drag a note to move it, or drag its right edge to change its length.
- Drag with **two fingers** to scroll and pinch to zoom. One finger always
  draws.
- The **scl** corner changes how the song's scale is shown: all notes, scale
  notes highlighted, or only the scale notes.

For a drum machine it's a step grid with one row per sound. Tap to add a hit.

## Recording

Press record, then play. What you play goes into the clip, quantised to the
clip's grid. You can set a count-in in the settings.

The keyboard is velocity sensitive by where you hit a key: low on the key is
soft, high is hard. The button in the bottom bar turns this off so every note is
full velocity. The drum pads have their own setting for the same thing.

Drag the row of controls just above the keys up or down to make the keyboard
taller or shorter.

## Per-note settings

Below the grid is a lane you can open. Pick what it shows, then drag in it to
set that value on each note.

- **note volume** - the note's velocity.
- **probability** - how often it plays. 50 means half the time.
- **trig condition** - when it's allowed to play. `1:4` plays on the first of
  every four loops. `pre` plays only if the previous conditional note played,
  `!pr` only if it didn't. `fil` plays only while fill is held, `!fi` only while
  it isn't.
- **ratchet** - how many times the note repeats within its step.
- **micro timing** - nudges the note off the grid, up to half a sixteenth either
  way.

The randomness uses a fixed seed, so you can get the same variation back when
you want it.

## Knobs

The machine's panel sits between the grid and the keyboard. Inserts, modifiers
and sends open the same kind of panel in a window.

**Hold a knob to reset it** to where it was when you opened the panel (not to
the factory value). Faders and sliders work the same way, including in the
mixer.

While **MIDI mapping** is on, holding a control clears its mapping instead.

## Automation

The strip under the per-note lane records and draws a parameter over time. Turn
a knob while recording and the movement is written there. You can also pick a
parameter and draw it in by hand.

## Folding things away

The lanes, the machine panel and the keyboard each fold away with the small
arrow at their edge, which gives the grid more room.
