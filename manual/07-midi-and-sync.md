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

### Exquis and Launchpad Pro

The Exquis and the Launchpad Pro have pages of their own:
[Exquis](07-midi-and-sync/exquis.md) and [Launchpad Pro](07-midi-and-sync/launchpad.md).

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
