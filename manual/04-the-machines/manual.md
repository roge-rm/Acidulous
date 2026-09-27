# Manual

> The organ: two manuals and pedals, four models and a rotary cabinet.

Manual is an organ with two keyboards and a pedalboard. They all share one set
of 91 tonewheels like the real thing, so the same wheel plays the fifth of one
note and the octave of another, which is why organ chords shimmer and beat a
little.

## Drawbars

Nine per manual, and they're most of the patch. Each one adds one harmonic of
the note, as loud as you pull it.

## The four models

**model** picks the kind of organ:

- **combo** - a transistor organ, buzzy with its own attack.
- **tonewheel** - the classic console organ, with **leakage** (wheels bleeding
  into each other), **hum** and **age**.
- **reeds** - a reed organ, with **pressure**, **buzz** and a tremulant.
- **pipes** - a pipe organ with **principal**, **flute**, **string**, **reed**
  and **mixture** stops, **chiff** on the attack and **tracker** noise.

Leakage, hum and blower wind noise fade in when the organ starts playing and
fade out about half a second after it stops, so a track that sits out a scene
is silent there.

## Key click

A real tonewheel organ's key contacts close one after another, so each note
starts with a little burst of clicks. **click** sets how loud that is,
**clickoff** is the click when you let go and **contacts** spreads the contacts
out. Without the click it sounds much less like an organ.

## The cabinet

The rotary speaker is built in: a spinning horn over a drum spinning the other
way, each with its own speed and ramp up and down, and two microphones you can
move. The rotors' position can be a modulation source too.

## Modulation

Two envelopes and two LFOs that can sync to the tempo, into eight matrix rows
(source, destination, depth).

Four of the sources come from the organ itself: **horn** and **drum** (where
the rotors are pointing), the **scanner** from the vibrato, and **wind** (the
air pressure). Horn and drum don't move while the rotary is stopped.

The destinations include each drawbar, and all the drawbars of a manual at
once.

## Tips

- Switch the rotary speed while playing. The speeding up and slowing down is
  the best part.
- Percussion takes over the last drawbar when it's on, like the originals.
  It's usually best left that way.
