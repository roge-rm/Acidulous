# MIDI and playing with others
You can use MIDI with a variety of keyboards and controllers, including MPE.
Special support has been added for the Intuitive Instruments Exquis and 
Novation Launchpad Pro MK3 (as I have these myself).

## Playing from a keyboard

USB and Bluetooth MIDI keyboards work straight away. Notes go to whichever
track is open, or to a track you pin so it stays the same whatever you're
looking at.

If a controller plays everything too loud or too soft, turn **velocity** on the
**notes** tab. **softer** brings the middle of the range down and **harder**
brings it up, and the softest and hardest notes stay where they are. The
readout next to it shows each note's velocity as the app gets it.

### MPE

With an MPE controller every finger has its own bend, pressure and slide, so
bending one note leaves the others alone. Leave **zone** on **auto** and the app
follows the controller, using the zone and bend range it announces or picking it
up as soon as two fingers are down on separate channels. The card says what it
found. Choose **lower** or **upper** to set the zone and bend range by hand.

What each gesture does:

- **Bend** moves the note's pitch on every melodic machine except Reflux, the
  organ and Diction, which bend as a whole. On a Draw harmonica played like a
  player, bending down bends with the tongue, as far as the hole goes.
- **Pressure** opens the sound up and makes it louder. On Brazen, Timber, Draw
  and Tongue it's breath, and on Filament it leans on the bow too. Hammer takes
  none: a piano has nothing to press once it's struck. **press** on a machine's
  panel sets how much.
- **Slide** (CC 74) brightens the note. It opens the filter on Trinity, Ratio
  and Mosaic, moves the pick up the string on Filament, tightens the lips or
  reed on Brazen and Timber, brings Draw's reeds closer to their slots, fits
  Tongue's reed tighter in its frame, and moves Cumulus's morph. **slide** on
  the panel sets how much. In Nexus, the **touch** module gives each voice its
  finger's pressure and slide to patch.

A keyboard that sends poly aftertouch presses each note on its own too, with no
zone needed. Other controllers on a finger's channel, like the mod wheel or the
sustain pedal, act on the whole track as usual.

Per-note bend, pressure and slide are recorded with the notes.

### Exquis

Plug in an Exquis over USB and its pads show the scale of the track it plays,
the track's own Scale modifier if it has one or the song's key if not, the same
as the piano roll. It changes when you open another track or change the scale.
With **pinned** it's the pinned track, and with **by channel** it's the track
for the channel the Exquis plays on. MPE fingers aren't routed by channel, so
with MPE it's the open track or the pinned one.

**exquis pads** in the MIDI window's devices tab chooses how:

- **own colours** sets the Exquis's own tonic and scale, so the pads show it in
  the colours you gave them. A scale the Exquis doesn't have shows as the
  nearest one that has all its notes, or chromatic.
- **highlight** lights the notes in the Exquis's highlight green instead, one
  pad per note, over whatever scale the Exquis is set to.
- **off** leaves the pads alone.

Its buttons work the app too: **play/stop** starts and stops the song,
**record** arms recording, **loop** loops the scene, **clips** switches to clip
mode, and **undo** and **redo** do what they say. Their lights follow the app,
so play is green while playing, record is red while armed, and loop and clips
are lit while they're on. The pads, knobs, slider and octave buttons stay the
Exquis's own. **exquis buttons** in the devices tab gives the buttons back to
the Exquis, and closing the app does too.

### Launchpad Pro

Plug in a Launchpad Pro [MK3] over USB and Acidulous takes over every pad and
button, lit in your tracks' colours. **launchpad** in the MIDI window's devices
tab gives it back, and so does closing the app. The buttons along the top choose
what the grid is:

- **Note** - a piano keyboard like the one on screen, with white keys on one row
  and black keys on the row above, four octaves up the grid. The notes in the
  scale (the track's own, or the song's key) are lit in the track's colour with
  the root brightest, and the rest are faint but still play. When the track has
  its own **Scale** modifier on, only the scale's notes are there, an octave a
  row from the root, which fits eight octaves. On a drum machine it's the
  machine's pads, laid out like on screen. Up and down change the octave.
- **Session** - the song grid, laid out like on screen with tracks down and
  scenes across. A clip pulses while it plays and flashes while it waits. Tap a
  clip to launch it in clip mode, or to play from that scene in song mode.
- **Sequencer** - the open track's clip in the current scene, eight steps at a
  time. Tap a pad to add or remove a note. Left and right move a step at a time,
  and up and down move through the notes. A drum machine's rows run down from
  the kick, like its grid on screen.
- **Custom** - the mixer, laid out like the song grid, with a row for each track
  and its fader running left to right. **Volume**, **Pan** and **Sends** on the
  bottom row choose what the faders are, and **Device** makes the rows the
  played machine's first eight knobs.
- **Chord** - chords in the key, a column for each note of the scale and a row
  for each kind of chord.
- **Projects** - the perform effects: repeat and gate along the top, reverse,
  tape stop, the riser, the three kills and an XY pad. Hold to play.

The buttons round the edge work on every page:

- **Play** and **Record** do what they say. **Shift** and **Play** stops
  everything.
- **Shift** and **Clear** undoes, and **Shift** and **Duplicate** redoes.
- Hold **Clear** and tap a clip to clear it. Hold **Duplicate** and tap a clip to
  copy it into the next scene if that's empty, or tap a scene button to
  duplicate the scene.
- The buttons down the right are the tracks, top to bottom like the grid's rows.
  Tap one to choose the track the Launchpad plays. Hold **Mute** or **Solo** and
  tap one to mute or solo it.
- The row under the grid is the scenes, left to right. Tap one to play it, like
  tapping a scene's header.
- On **Session** and **Custom**, up and down move through the tracks and left and
  right through the scenes, one at a time. On the other pages, hold **Shift** to
  do the same. An arrow is lit when there's more that way.
- **Quantise** quantises the open clip the way the quantise window was last set.
- **Stop Clip** stops the clips, or the song.

Its pads send velocity and pressure, and both are recorded like any keyboard.

### Pedals

A pedal plugged into your keyboard works on every melodic machine:

- **Sustain** (the right pedal) keeps notes sounding after you let go of the
  keys, until you lift it. On Filament it also lifts the dampers, so the strings
  you aren't playing ring along.
- **Sostenuto** (the middle one) holds only the keys that were down when you
  pressed it, and notes you play after that stop as usual.
- **Soft** (the left one) plays notes in more quietly while it's down.

Hammer takes a pedal part of the way down, as a piano does: half a sustain pedal
lets the dampers only touch the strings. Every other machine hears a pedal as
up or down, with the halfway point counting as down. MIDI out sends the pedal as
far down as it is.

Pedals are recorded as lanes in the automation strip, one each, and you can draw
them there by hand too. On Hammer a lane keeps how far down the pedal was, and
where it moved; on everything else it's up or down. Drum machines ignore them.

## Mapping a controller

Long-press redo to enter mapping mode. Controls that can be mapped are
highlighted. Tap one, then move a knob or press a key on your controller to link
them. Knobs, faders, mixer controls and transport buttons can all be mapped to a
CC or a note.

A mapped knob records into automation like moving it by hand. Mapped buttons
like play, stop and fill don't record.

## Clock

The app can send MIDI clock (with start, stop and song position) to hardware,
and follow an incoming clock, taking its tempo from the other device.

**follow** on the MIDI window's **control** tab has three settings:

- **on** always follows, even with nothing coming in.
- **auto** follows a clock when one arrives and goes back to the song's own
  tempo a second after it stops.
- **off** ignores incoming clock.

**send**, next to it, turns clock out on and off. Whether a track's notes go out
is set per track, in its settings or on its mixer strip.

## Link

Ableton Link shares tempo and bar position with other Link apps on the same
network, both ways. Turn it on and the number of connected apps shows up. It
keeps everyone's downbeat together without anyone being in charge.
