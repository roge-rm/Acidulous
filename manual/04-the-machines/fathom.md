# Fathom

> Modelled water and weather: bubbles, drips, rain, streams, surf, wind,
> fire, thunder, hail, waterfalls, sizzling, ice, snow, splashes, the deep and
> a rainstick, played from the keys.

Fathom doesn't play recordings. Most of the sound of water is bubbles: a
pocket of air caught under the surface rings like a spring, at a pitch set
by its size (the smaller, the higher), and rises in pitch as it nears the
top. A drip is a bubble and the tap of the drop; rain is many drips, and
the sound of what they land on; a stream is a dense cloud of small bubbles
and a few big ones. Surf is the rush of a wave rising and falling back. Wind
is air rushing past things in gusts, whistling where it's caught in a gap;
fire is crackles, hiss and roar.

Hold a note to keep it going. The note sets the size of the bubbles, or the
note the wind whistles; the velocity sets how much.

## Water

- **model** - **bubbles**, **drips**, **rain**, **stream**, **surf**,
  **wind**, **fire**, **thunder** (a rolling rumble, with a crack when the
  tone is bright and it's close), **hail**,
  **waterfall**, **sizzle** (steam and frying), **ice** (cracks in a frozen
  sheet), **snow** (footsteps), **splash**, **underwater** or **rainstick**
  (pebbles falling, thinning out over the decay).
- **tune** - in cents.
- **density** - how many bubbles, drops or crackles. At 0, one comes with
  each note, so you can play a tune with them.
- **size** - how far the bubbles' sizes, and so their pitches, spread from
  the note, in semitones.
- **rise** - how far a bubble's pitch climbs as it nears the surface.
- **decay** - how long each bubble rings.
- **surface** - what the rain or hail falls on: **water**, **leaves**,
  **tin** or a **window**.

## Key

The **key** card puts the bubbles and drips in key, so the water plays
along with the song.

- **snap** - how many of them land on a note of the key. At 0 they're free,
  at 1 every one is on a note, in whatever octave it's nearest.
- **onto** - **chord**, the notes you're holding on the track, or
  **scale**, the scale and key set next to it. With chord, hold or play a
  new chord and the next bubbles take its notes.
- **scale** and **key** - the scale for **onto scale**.

The notes follow the track's tuning. Rain on water catches more bubbles as
the snap goes up, so it sings the chord. Turn the **rise** down for bubbles
that hold their note, and the **size** up to spread them over more octaves.
Wind, fire, hail and the roof or window the rain falls on aren't pitched,
so the key doesn't move them.

## Weather

- **gust** - how much the wind, the fire and the waves come and go.
- **whistle** - wind caught in a gap, whistling on the note.
- **tone** - darker or brighter.
- **waves** - how many beats from one wave to the next. It follows the
  tempo.

## Player

- **spread** - how far apart the bubbles and drops land, left to right.
- **fade** - how long it takes to come in when a note's held, and to die
  away when it's let go.
- **velocity**, **octave**, **bend** and **volume**.

Pressure, or the mod wheel, thickens it.

## Kit

Turn on **kit** and the keys from C2 up are sixteen pads, each its own sound, so one track can play a whole scene: rain on one pad, wind on another, a fire on a third. The sequencer shows the drum grid, one lane per pad. Up to four pads sound at once.

- Tap a pad on the drum grid, then set it in the pad card: **sound**, **pitch**, **density** and **level**.
- The other knobs (size, rise, tone and so on) work on every pad.
- **Weather Kit** starts with fire, wind, rain, drips, bubbles, a stream and surf, **Storm Kit** with rain, hail, wind, gusts and thunder near and far, and **Water Kit** with bubbles, drips, a stream, splashes, a waterfall and the deep.

## Macros

The **play** page has eight macro knobs. A patch can name them (Storm,
Near, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves every sound, 24 semitones at 1, and snap brings
the water into key. The model, tune, surface, octave, bend, the key's
**onto**, **scale** and **key**, and a kit's pad sound, pitch and level
can't be reached. In a kit the routes move the knobs every pad shares, and density
moves each pad's own density. A change to the size, rise, decay or spread is
heard from the next bubble, drop or crackle; the rest move at once. Tap a
macro's name to rename it.

## Tips

- **Bubble Notes** and **Drip Notes** have no density: each note is one
  bubble or drip, tuned, for melodies.
- **Whistling Wind** plays the note you hold: try slow chords.
- **Bubble Chords**, **Tuned Drips** and **Rain in the Key** follow the
  chord you hold, and **Pentatonic Brook** a scale: set its key to the
  song's.
