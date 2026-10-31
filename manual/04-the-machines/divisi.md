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
- **phaser** **depth**, **rate** and **feedback**.

## Notes

- **tune** (in cents), **octave**, **bend**, **velocity** and **volume**.

Takes the song's tuning, and pressure swells each note. With MPE each finger
bends and swells its own note.

## Macros

The **play** page has eight macro knobs. The patches name them Bright, Swell,
Longer, Lush, Voices, Brass, Phase and Low, or the opposite where a patch
already has a lot of one (Dark, Sharper, Shorter, Drier, Vowel, Blat, Still,
Light). They sit at zero when a patch loads, which is the patch as it was
made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves every note, 24 semitones at 1. The mode, tune,
octave and bend can't be reached. Tap a macro's name to rename it.

## Tips

- A big chord with every register up at full velocity gets loud. Pull the
  velocity or volume down a little when you stack everything.
- **Para Brass** and **Brass Swell** share one filter: hold a chord and add
  notes to it, rather than playing each note fresh.
