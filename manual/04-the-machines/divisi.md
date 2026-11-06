# Divisi

> A string ensemble: violins down to contrabass, a choir and brass, all
> through a thick chorus and a phaser.

Divisi plays like the old string machines. Twelve oscillators, one for each
note name, are divided down in octaves, so every C on the keyboard comes from
the same oscillator and they all stay in step. Each key you hold switches its
note into six registers, and each register has its own level.

By default one envelope swells the whole sound. A key you press while others
are held joins the sound as it is, and the release only starts once every key
is up. Switch **mode** to **poly** to give every note its own attack and
release.

## Registers

- **violin** - bright, at the note.
- **viola** - darker, at the note.
- **cello** - an octave down.
- **contrabass** - two octaves down.
- **tone** - darker or brighter, on all four.

## Choir

- **level** - the choir's level.
- **vowel** - from **u** through **o**, **a** and **e** to **i**, dark to
  bright.

## Brass

A saw of its own for each key, through a filter that opens with the note,
overshoots and falls back while the note's held.

- **level** - the brass's level.
- **sweep** - how far the filter opens.
- **decay** - how long a held note takes to fall back.
- **blat** - the bite at the start: how far the filter overshoots, how late it
  opens and how far the pitch scoops up into the note.

In paraphonic mode the keys share one brass filter, opened by the first key,
so play it legato. In poly mode each key has its own.

## Ensemble

- **mode** - **para** (one envelope for all the keys) or **poly** (one each).
- **attack** and **release** - up to 5 and 10 seconds.
- **ensemble** **depth** and **speed** - the chorus: two delay lines a side,
  each moved by a slow and a fast wobble, the right side the opposite way to
  the left. At 0 you hear the oscillators as they are.
- **age** - the chorus from clean to an old, worn box: the highs go, a little
  hiss comes in that rises and falls with the sound, the pitch wobbles more
  and loud chords press into it. Past half way a faint whine follows the
  chorus's sweep. It ages the sections' own choruses on the stage too.
- **phaser** **depth**, **rate** and **feedback**.

## Players

Each register is a section of players. More players make a thicker sound,
each a little off from the others.

- **players** - how many in each section, 1 to 16. One player with no drift
  or vibrato is the plain divided-down sound.
- **drift** - how far each player's pitch wanders, slowly, in cents.
- **vibrato** and **rate** - each player's vibrato, its depth in cents. Each
  player's is a little faster or slower than the others'.
- **scatter** - how late a player can come in, up to 80 ms. The order changes
  each time you play from silence.

The players sit across each section's width. The more drift, the later the
sound: up to about 20 ms at full.

## Stage

Each section has a seat on a stage: violins at the front on the left, violas
just right of the middle, cellos on the right, basses behind them, brass at
the back left of the middle and the choir at the back. A section further back
comes in later, quieter and duller, and each section has its own chorus.

- **spread** - how far the sections spread out over the stage. At 0 they all
  sit in the middle and share one chorus. At full the choir comes in about
  30 ms after the violins.
- **room** - the stage's own sound: reflections off the side walls and a
  short, dark ambience of under a second. The back rows are heard mostly
  through the room, the front rows mostly direct. It isn't a hall: put a
  reverb after it for one.
- **split** - each section keeps to its own part of the keyboard: basses at
  the bottom, then cellos, violas and violins at the top, with the choir and
  brass in the middle. A chord fans out over the stage and a rising line
  moves from the cellos to the violins. It sets each note as it's played.
  Sections that are off leave their part to the next one.

## Motion

Motion moves the sections slowly while you hold a chord, as if they were
listening to each other.

- **shape** - what moves:
  - **drift** - each section wanders on its own, louder and softer, brighter
    and darker.
  - **swell** - the whole ensemble breathes together, quieter and darker at
    the bottom of each breath. A chord played from silence starts at the
    bottom.
  - **tide** - one wave rolls across the stage, from the violins at the front
    left to the basses at the back right.
  - **steps** - a new balance every step, like a conductor bringing sections
    in and out. Each change glides in over the start of its step.
- **rate** - how fast, from one step or wave in 50 s to two a second.
- **sync** - on **free** it uses the rate. Otherwise a step or wave is a note
  value or a number of bars. While the song plays it follows the song's
  position, so a bar sounds the same every time.
- **balance** - how far each section's level moves, up to about 6 dB either
  way, the sections together staying as loud. On swell it's the breath, up to
  12 dB down.
- **vowel** - how far the choir's vowel moves.
- **tone** - how far each section gets brighter or darker, up to an octave
  either way.
- **ensemble** - how far each section's chorus gets deeper or shallower.

Drift and tide are easiest to hear with **spread** up, so the sections sit
apart.

## Notes

- **tune** (in cents), **octave**, **bend**, **velocity** and **volume**.

Takes the song's tuning, and pressure swells each note. With MPE each finger
bends and swells its own note.

## Macros

The **play** page has eight macro knobs. The patches name them Bright, Slow,
Lush, Voices, Brass, Phase, Motion and Age, or the opposite where a patch
already has a lot of one (Dark, Quick, Drier, Vowel, Blat or Smooth, Still,
Calm, Newer). Slow lengthens the attack and release together. A patch with no
motion has Low (the contrabass) or Light in Motion's place. They sit at zero
when a patch loads, which is the patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch moves every note, 24 semitones at 1. The mode, tune,
octave, bend and the motion's shape and sync can't be reached. The players
move in whole players, and split changes the notes you play after it. Tap a
macro's name to rename it.

## Tips

- A big chord with every register up at full velocity gets loud. Pull the
  velocity or volume down a little when you stack everything.
- **Brass Swell** and **Dusty Brass** share one filter: hold a chord and add
  notes to it, rather than playing each note fresh.
- The patches come in seven families: strings, stage (the sections seated),
  low, choir, brass, motion and worn (an old chorus, some on tape). Most
  bring their own hall, plate, wooden body or tape as inserts.
