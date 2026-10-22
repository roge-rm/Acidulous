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

## The swell pedal

**swell** in the **touch** group on the **out** page picks what works the swell
pedal: off, the mod wheel, pressure or **ped**, an expression pedal on CC 11.
**ped** starts open until the pedal sends something. Closed, the organ plays at
a quarter of its level. Turn up **shutters** and closing it also makes the sound darker, like
the shutters of a pipe organ's swell box. At 0 the pedal only changes the
level.

## Bar glide

**bar glide** on the **bars** page is how long the drawbars, tabs, ranks and the
morph take to get to a new setting, whether you move them by hand or with a
lane, a macro or the matrix. At 0 they move straight away. With a second or two
a drawbar pulled under a held chord opens the sound up slowly.

## Macros

The **play** page has eight macro knobs. A patch can name them (Pull, Full,
etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Every drawbar is a destination. Pitch moves the whole instrument,
24 semitones at 1. The model, the splits, the switches and stepped choices, the
matrix and the tuning steps can't be reached. A change to the attack, release,
click, contacts, chiff, percussion time or keytrack or velocity is heard from
the next note. Tap a macro's name to rename it.

## Tips

- Switch the rotary speed while playing. The speeding up and slowing down is
  the best part.
- Percussion takes over the last drawbar when it's on, like the originals.
  It's usually best left that way.
