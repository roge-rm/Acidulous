# MIDI and playing with others
> Keyboards, controller mapping, clock, MPE and Link.

Everything here is under **MIDI…** in the file menu.

## Playing from a keyboard

USB and Bluetooth MIDI keyboards work directly. Notes go either to whichever
track is open, or to a track you pin so it stays the same whatever you're
looking at.

If a controller plays everything too loud or too soft, turn **velocity** on the
**notes** tab. **softer** brings the middle of the range down and **harder**
brings it up; the softest and hardest notes stay where they are. The readout
next to it shows each note's velocity as the app gets it.

### MPE

With an MPE controller every finger has its own bend, pressure and slide, so
bending one note leaves the others where they are. Leave **zone** on **auto**
and the app follows the controller: it uses the zone and bend range the
controller announces, or recognises it the moment two fingers are down on
separate channels. The card says what it found. Choose **lower** or **upper**
to set the zone and bend range by hand instead.

What each gesture does:

- **Bend** moves the note's pitch, on every melodic machine except Reflux and
  the organ, which bends as a whole.
- **Pressure** opens the sound up and makes it louder. On Brazen and Timber it
  is breath, and on Filament it leans on the bow as well. **press** on a
  machine's panel sets how much.
- **Slide** (CC 74) brightens the note: it opens the filter on Trinity, Ratio
  and Mosaic, moves the pick up the string on Filament, tightens the lips or
  reed on Brazen and Timber, and walks Cumulus's morph. **slide** on the panel
  sets how much. In Nexus, the **touch** module gives each voice its finger's
  pressure and slide to patch.

A keyboard that sends poly aftertouch presses each note on its own too, with
no zone needed. Other controllers sent on a finger's channel, like the mod
wheel or the sustain pedal, act on the whole track as usual.

Per-note bend, pressure and slide are recorded with the notes.

### Exquis

Plug in an Exquis over USB and its pads show the scale of the track it plays:
the track's own Scale modifier if it has one, otherwise the song's key. The
same as the piano roll shows. It changes when you open another track or
change the scale. With **pinned** it's the pinned track, and with **by
channel** it's the track for the channel the Exquis plays on. MPE fingers
aren't routed by channel, so with MPE it's the open track or the pinned one.

**exquis pads** in the MIDI window's devices tab chooses how:

- **own colours** sets the Exquis's own tonic and scale, so the pads show it
  in the colours you gave them. A scale the Exquis doesn't have shows as the
  nearest one that has all its notes, or chromatic.
- **highlight** lights the notes in the Exquis's highlight green instead,
  one pad per note, over whatever scale the Exquis is set to.
- **off** leaves the pads alone.

Its buttons work the app too: **play/stop** starts and stops the song,
**record** arms recording, **loop** loops the scene, **clips** switches to
clip mode, and **undo** and **redo** do what they say. Their lights follow
the app: play is green while playing, record red while armed, and loop and
clips lit while they're on. The pads, knobs, slider and octave buttons stay
the Exquis's own. **exquis buttons** in the devices tab gives the buttons back
to the Exquis; they're given back when the app closes as well.

### Launchpad Pro

Plug in a Launchpad Pro [MK3] over USB and Acidulous takes it over: every
pad and button is the app's, lit in your tracks' colours. **launchpad** in the
MIDI window's devices tab gives it back to itself, and so does closing the
app. The buttons along the top choose what the grid is:

- **Note** - the played track's scale (its own, or the song's key) laid out as
  the Launchpad's own scale mode does,
  each row a fourth up. The root pads are in the track's colour. On a drum
  machine it's the machine's pads. Up and down change the octave; left and
  right move along the scale.
- **Session** - the song grid, tracks across and scenes down. A clip pulses
  while it plays and flashes while it waits. Tap a clip to launch it in clip
  mode, or to play from that scene in song mode. The arrows move around the
  grid.
- **Sequencer** - the open track's clip in the current scene, eight steps at a
  time. Tap a pad to add or remove a note. The arrows move along the steps and
  up and down the notes.
- **Custom** - the mixer: each column is a track's fader. **Volume**, **Pan**
  and **Sends** on the bottom row choose what the faders are, and **Device**
  makes them the played machine's first eight knobs.
- **Chord** - chords in the key, one column for each note of the scale and one
  row for each kind of chord.
- **Projects** - the perform effects: repeat and gate along the top, reverse,
  tape stop and the riser, the three kills, and an XY pad. Hold to play.

The buttons round the edge work on every page:

- **Play** and **Record** do what they say. **Shift** and **Play** stops
  everything.
- **Shift** and **Clear** undoes; **Shift** and **Duplicate** redoes.
- Hold **Clear** and tap a clip to clear it. Hold **Duplicate** and tap a clip
  to copy it into the empty scene below, or tap a scene button to duplicate the
  scene.
- The row under the grid chooses the track the Launchpad plays. Hold **Mute**
  or **Solo** and tap a track to mute or solo it.
- The buttons down the right play scenes, as tapping a scene does.
- **Quantise** quantises the open clip as the quantise window was last set.
- **Stop Clip** stops the clips, or the song.

Its pads send velocity and pressure, and both are recorded like any keyboard.

### Pedals

A pedal plugged into your keyboard works on every melodic machine:

- **Sustain** (the right pedal) keeps notes sounding after you let go of the
  keys, until you lift it. On Filament it also lifts the dampers, so the
  strings you aren't playing ring along.
- **Sostenuto** (the middle one) holds only the keys that were down when you
  pressed it. Notes you play after that stop as usual.
- **Soft** (the left one) plays notes in more quietly while it's down.

Pedals are recorded as lanes in the automation strip, one each, and you can
draw them there by hand too. Drum machines ignore them.

## Mapping a controller

Long-press redo to enter mapping mode. Controls that can be mapped are
highlighted. Tap one, then move a knob or press a key on your controller to link
them. Knobs, faders, mixer controls and transport buttons can all be mapped, to
a CC or a note.

A mapped knob records into automation just like moving it by hand. Mapped
buttons like play, stop and fill don't record.

## Clock

The app can send MIDI clock (with start, stop and song position) to hardware,
and it can follow an incoming clock. When following, the tempo comes from the
other device.

**follow** on the MIDI window's **control** tab has three settings:

- **on** always follows, even with nothing coming in.
- **auto** follows a clock when one arrives and goes back to the song's own
  tempo a second after it stops.
- **off** ignores incoming clock.

Each track chooses what it sends out: notes, clock, or both.

## Link

Ableton Link shares tempo and bar position with other Link apps on the same
network, both ways. Turn it on and the number of connected apps appears. It
keeps everyone's downbeat together without anyone being in charge.
