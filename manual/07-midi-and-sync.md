# MIDI and playing with others
> Keyboards, clock, MPE and Link.

**MIDI…** in the file menu is where all of this lives, in the order somebody
asks the questions: what is coming in, where it goes, and what goes out.

## Playing the app from a keyboard

USB and Bluetooth keyboards are handled by the app itself. Arriving notes go
either to whichever track is open - so changing track changes what you are
playing - or to a track you pin, so it stays put whatever you are looking at.

**Per-note expression** is understood where a machine can use it. A controller
that bends one finger without bending the others will bend one note without
bending the others, on the machines that have somewhere to put it, and the zone
and bend range are set here. It is recorded as well as played: a note remembers
its own bend, pressure and slide.

## Mapping a controller

Long-press redo to enter mapping mode. Every control that can be mapped says so,
and tapping one arms it: move a knob or press a key on your controller and the
two are joined. Knobs, faders, mixer controls and transport buttons can all be
mapped, to a CC **or** to a note.

A mapped parameter records into the automation lane for free, because it moves
down the same path your finger does. A mapped action - play, stop, fill - never
records, because a button press is not a value.

## Clock

The app can send MIDI clock to hardware, with start, stop and song position, and
it can follow somebody else's. Following means the tempo control goes quiet: the
tempo is theirs.

What goes out is set per track: notes, or clock, or both.

## Link

Link shares tempo and the position within the bar with any other Link app on the
same network, in both directions. Switch it on and the peer count appears. It
carries the beat and the bar, not the song position - so everybody agrees on
where the downbeat is without anybody being in charge.
