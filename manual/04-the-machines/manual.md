# Manual

> The organ: two manuals and pedals, four models, and a rotary cabinet.

Manual is an organ with two keyboards and a pedalboard. They all share one
generator of 91 tonewheels, like the real thing, so the same wheel plays the
fifth of one note and the octave of another. That's why organ chords shimmer
and beat slightly.

## Drawbars

Nine per manual, and they're most of the patch. Each one adds one harmonic of
the note, as loud as you pull it.

## The four models

**model** picks the kind of organ:

- **combo** - a transistor organ. Buzzy, with its own attack.
- **tonewheel** - the classic console organ, with **leakage** (wheels bleeding
  into each other), **hum** and **age**.
- **reeds** - a reed organ, with **pressure**, **buzz** and a tremulant.
- **pipes** - a pipe organ with **principal**, **flute**, **string**, **reed**
  and **mixture** stops, **chiff** on the attack and **tracker** noise.

Leakage, hum and blower **wind noise** fade in when the organ starts playing and
fade out about half a second after it stops, so a track that sits out a scene
is silent there.

## Key click

A real tonewheel organ's key contacts close one after another, so each note
starts with a little burst of clicks. **click** sets how loud that is,
**clickoff** is the click when you let go, and **contacts** spreads the
contacts out. With the click off it sounds much less like an organ.

## The cabinet

The rotary speaker is built in: a spinning horn over a drum spinning the other
way, each with its own speed and its own ramp up and down, and two microphones
you can move. The rotors' position can also be used as a modulation source.

## Modulation

Two envelopes and two LFOs that can sync to the tempo, into eight matrix rows
(source, destination, depth).

Four of the sources come from the organ itself: **horn** and **drum** (where
the rotors are pointing), the **scanner** from the vibrato, and **wind** (the
air pressure). Horn and drum don't move while the rotary is stopped.

The destinations include each drawbar, and all the drawbars of a manual at
once.

## Tips

- Switch the rotary speed while playing rather than leaving it on one setting.
  The speeding up and slowing down is the best part.
- Percussion takes over the last drawbar when it's on. That's how the originals
  worked, and it's usually best left that way.
