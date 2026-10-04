# Tongue

> A modelled jaw harp: a reed ringing through a slot, and a mouth that picks
> out its harmonics.

Tongue doesn't play recordings. A steel reed is plucked and swings through a
slot in its frame, and each time it passes through it pushes a puff of air.
That's the buzz, with every harmonic in it at much the same level. Your mouth
is in front of it, and as it changes shape it brings one harmonic forward and
then another: that's the tune, over a drone that stays on the note you play.

There are ten kinds of harp, and the metal ones were fitted to recordings of
real ones. A harp can have up to five reeds, tuned as a chord.

The mod wheel moves the mouth, so you can play the drone with one hand and the
tune with the other. Pressure is breath.

## Harp

- **model** - the kind of harp:
  - **steel** - the common steel harp, with a long ring.
  - **munnharpe** - a low Norwegian harp, soft and round.
  - **khomus** - a Sakha harp, darker on top than steel, often breathed
    through as it's played.
  - **morsing** - a big South Indian harp: low, dark and short, for rhythm.
  - **temir komuz** - a Kyrgyz harp: a close fit, bright and ringing.
  - **brass** - a thin brass harp cut from its own frame: high, buzzy and
    quiet, with the frame ringing too.
  - **bamboo** - a reed cut from a strip of bamboo: soft, airy and short,
    with a woody tock.
  - **mukkuri** - a bamboo harp played by pulling a string tied to its frame.
  - **genggong** - a palm harp, also string-pulled: a quick tug and a buzz.
  - **kouxian** - a harp of several reeds, three tuned to a pentatonic chord
    unless you set **reeds** and **chord** yourself.
- **tune** - in cents.
- **set** - where the reed rests in the slot. In the middle the puffs come
  evenly and the odd harmonics are louder; off to one side the even ones come
  up with them.
- **fit** - how closely the reed fits the slot. A close fit makes sharper
  puffs and more high harmonics; a loose one is softer and hollower.
- **ring** - how long a steel harp rings, in seconds. The other kinds ring
  longer or shorter than that.

**set**, **fit** and **pluck** start where each kind sits, so a morsing with
those knobs at their defaults is already a morsing.

## Pluck

- **pluck** - how sharp the flick is. Up is a quick flick with a click in it,
  down is a soft push with the pad of the finger.
- **overtones** - how much of the pluck goes into the reed's own overtones,
  the metallic ping at the start.
- **velocity** - how much playing harder changes the level.

Pluck a key that's still ringing and the finger catches the reed first, so it
doesn't keep getting louder. The string-pulled kinds are drawn back and let
go instead, and **pluck** sets how quickly.

## Reeds

- **reeds** - how many reeds the harp has, or **auto** for the kind's own.
- **chord** - how they're tuned against the note you play: **unison** (a few
  cents apart), **octaves**, **fifths**, **major**, **minor** or
  **pentatonic**, or **auto** for the kind's own.
- **strum** - the time between one reed and the next, in ms. At 0 they're
  plucked together.
- **order** - **up** or **down** the chord, **scatter** in a different order
  each time, or **in turn**: each note plucks only the next reed, and the
  others keep ringing, so playing a rhythm on one key plays the chord.

## Mouth

- **vowel** - the shape of the mouth: oo, oh, ah, eh, ee. The mod wheel adds to
  it.
- **focus** - how narrow the mouth's resonances are. Up, each one picks out a
  single harmonic and the tune gets clearer.
- **depth** - how much of the sound goes through the mouth.
- **glide** - how long the mouth takes to move to a new vowel, in ms.

The mouth keeps the level steady, so moving it changes the colour and not the
loudness.

## Breath

- **breath** - air blown through the harp while you play. Pressure adds to it.
- **air** - how much of that air you hear as a hiss.
- **sustain** - breath hard enough to keep the reed going while the key is
  down. With **breath** and **sustain** both up, a held note doesn't die.

## Out

- **stop** - letting go of a key puts a finger on the reed. Down, it rings on;
  up, it stops quickly.
- **voices** - one harp, or up to four. With one, a new note replucks the same
  harp at the new pitch.
- **octave**, **bend** - the octave, and the pitch bend range in semitones.
- **volume**.
