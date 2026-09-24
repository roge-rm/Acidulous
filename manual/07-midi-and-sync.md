# MIDI and playing with others
> Keyboards, controller mapping, clock, MPE and Link.

Everything here is under **MIDI…** in the file menu.

## Playing from a keyboard

USB and Bluetooth MIDI keyboards work directly. Notes go either to whichever
track is open, or to a track you pin so it stays the same whatever you're
looking at.

**MPE** works on the machines that support it: bending one finger bends only
that note. You set the zone and bend range here. Per-note bend, pressure and
slide are recorded too.

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
