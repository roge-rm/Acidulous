# The editor
A clip opens with the note grid at the top, the machine's controls in the
middle, and the keyboard or pads at the bottom.

## The grid

For a melodic machine it's a piano roll, with pitch up the side and time
across.

- Tap an empty square to add a note, and tap a note to delete it.
- Drag a note to move it, or drag its right edge to change its length.
- Drag with **two fingers** to scroll and pinch to zoom. One finger always
  draws.
- The **scl** corner changes how the song's scale is shown: all notes, scale
  notes highlighted or only the scale notes.

For a drum machine it's a step grid with one row per sound. Tap to add a hit.

## Generating

The die button at the top of the editor writes notes for you. The clip changes
as you turn the knobs, so you can listen while the song plays. **OK** keeps the
notes and **Cancel** puts the clip back, and undo takes back the whole thing in
one step.

- **rhythm** spreads a number of hits as evenly as it can over a number of
  steps, and **turn** moves the pattern round. A pattern shorter than the bar
  drifts against it.
- **line** writes a line in the track's scale or the song's key, or a minor
  pentatonic if there's neither. **leaps** is how often it jumps instead of
  moving to a nearby note.
- **mutate** changes some of the notes already there: some move a step in the
  scale, some go, a few new ones appear and velocities shift. **amount** is how
  much.

On a drum machine, rhythm and **scatter** work on one sound and leave the
others alone, and mutate moves drums in time instead of pitch.

**roll** gives a new set of random choices. The same settings and roll always
give the same notes.

## Locking a step

A lock gives one step its own value for a knob, like tuning the snare on step 7
higher than all the others.

- Tap the diamond (◆) at the top of the editor. It lights, and the grid gets a
  pink edge.
- Tap the steps to lock: hits in the drum grid, steps in Reflux's step row or
  notes in the roll. Tap again to let one go.
- Turn any knob on the panel below, the machine's or an effect's. The chosen
  steps get that value and the knob stays put for the rest of the clip.
- Hold a knob to take the lock off the chosen steps.
- Tap the diamond again to go back to drawing notes.

A lock lasts one grid step, or the note's length in the roll. Locked steps have
a pink diamond in the corner, and a knob with locks on it shows ◆ on its dial.

Turn the knob later without a step chosen and every unlocked step follows it.
A knob that already has a drawn lane can't be locked, and shows ∿ instead.

## Recording

Press record, then play, and what you play goes into the clip on the clip's
grid. The **record** card in the tempo window's **click** tab sets a count-in
and whether recording quantises. **off** leaves notes exactly where you played
them, and **amount** moves them only part of the way.

Each take is one step of undo, from when recording and playback are both
running until either stops.

The keyboard plays softer low on a key and harder high on it. The button in the
bottom bar turns this off so every note is full velocity, and the drum pads have
their own setting for it.

Drag the row of controls just above the keys up or down to make the keyboard
taller or shorter.

## Quantising

**⊞** in the header (or **Q**) opens the quantise window. It works on the
selected notes, or the whole clip if none are selected, and you hear the changes
as you make them. **OK** keeps them as one step of undo and **Cancel** puts
everything back.

- **grid** - the clip's own grid, or another.
- **amount** - how far each note moves towards the grid. Less than all the way
  tightens a part and keeps its feel.
- **move** - just the starts, or the ends too.
- **as played** - puts notes back exactly where they were played. Recorded
  notes always remember this, however often they're quantised.
- **groove** - quantises to another clip's timing instead of a straight grid,
  like a drummer's lazy backbeat. The song's swing is added when it plays, so
  quantise to a straight grid for swing.
- **humanise** - each press adds small random changes to timing, loudness and
  length. Press again for a different result, or **none** to take them away.
  The knob sets how much.

## Per-note settings

Below the grid is a lane you can open. Pick what it shows, then drag in it to
set that value on each note.

- **note volume** - the note's velocity.
- **probability** - how often it plays. 50 means half the time.
- **trig condition** - when it's allowed to play. `1:4` plays on the first of
  every four loops. `pre` plays only if the last conditional note played, and
  `!pr` only if it didn't. `fil` plays only while fill is held, and `!fi` only
  while it isn't.
- **ratchet** - how many times the note repeats in its step.
- **micro timing** - nudges the note off the grid, up to half a sixteenth either
  way.

The randomness uses a fixed seed, so the same variation comes back every time.

## Knobs

The machine's panel sits between the grid and the keyboard. Its controls are in
sections, and the tabs along its top show one section at a time. Inserts,
modifiers and sends open the same kind of panel in a window.

Hold a knob to reset it to where it was when you opened the panel. Faders and
sliders work the same way, including in the mixer.

While **MIDI mapping** is on, holding a control clears its mapping instead.

## Automation

The strip under the per-note lane records and draws a parameter over time. Turn
a knob while recording and the movement is written there, or pick a parameter
and draw it in by hand.

A knob with a lane in the open clip has a **∿** on its dial, and the lane moves
it while the clip plays.

When you press play, every automated knob goes back to where it's set in the
song first, so the song starts the same every time, and so does an export. In
the launcher it doesn't, and a clip you launch carries on from wherever the
last one left things.

## Folding things away

The lanes, the machine panel and the keyboard each fold away with the small
arrow at their edge, to give the grid more room.

## On a square screen

On a phone about as wide as it is tall, the grid takes the top of the screen
and the keyboard or the machine panel takes the bottom, one at a time.
**keys** at the start of the bottom bar (**pads** on a drum machine) swaps
between them, and **fx** and the mixer bring the panel up.

Windows fit the screen there too, without scrolling. The few that wouldn't fit
are split into pages. The arp is in three (**time · feel**, **pattern**,
**chance · run**), and the tempo window's key and the Sound window's input each
have a tab of their own.
