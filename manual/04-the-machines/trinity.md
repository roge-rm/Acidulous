# Trinity

> Three oscillators, wavetables, two filters and a mod matrix: the all-rounder.

Trinity is the general-purpose poly synth, three oscillators into two filters
with a modulation matrix on top.

## Oscillators

Each oscillator plays a wavetable, a row of waveshapes that **pos** moves
through. Automate **pos** to sweep the tone.

**wave** picks saw, square, triangle or sine, or one of 32 wavetables of eight
shapes each: plain additive ones (Hollow, Stack, Even), filter and sync sweeps
(Reso, Sync), FM and bent shapes (Index, Reed, Bent), voices (Vowel, Choir,
Nasal), instruments (Bow, Brass, Pluck, Organ, Bell) and digital and strange
ones (Crush, Stair, Chip, Dust, Spiral).

- **density** stacks copies of the oscillator, **detune** spreads them out and
  **drift** makes each copy wander a little. This is how you get a big
  supersaw sound.
- **hard** is oscillator sync and **pw** is pulse width.
- **fm21** and **fm32** are FM between the oscillators (2 into 1, 3 into 2),
  for metallic and bell sounds.
- **noise** and its **colour** are for breath, snare tails, etc.

## Filters

There are two. **route** puts them in series, in parallel or split with
**balance** between them, and each has its own type, drive, key tracking and
envelope amount.

## Modulation

Six envelopes and three LFOs feed twelve matrix rows.

The **a** envelope is the amplitude and **f** is the filter, and the other four
do nothing until you route them. Each LFO has a wave, a rate that can sync to
the tempo, delay, phase, key sync, one-shot and slew.

A matrix row has a source, a second source, a destination and a depth. The
second source scales the first, so an envelope with the mod wheel as its second
source is an envelope you can fade in with the wheel.

Two kinds of destination move an LFO's rate. **l1rate** (and l2, l3) moves it
smoothly, up to four octaves either way. **l1step** moves it in whole octaves,
one for each quarter of depth. A synced LFO goes from 1/16 to 1/32 and so on
and stays on the beat, since it changes at the start of a cycle, and a free
one doubles or halves its rate.

The mod wheel also opens both filters, whatever the matrix says, and **wheel**
in the voice section sets how much. At zero the wheel only does what the matrix
tells it to.

## Macros

The **play** section has eight macro knobs. A patch can name them (Open,
Bite, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** section sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob adds knob times amount to the destination, like a matrix row with the
knob as its source. Tap a macro's name to rename it.

## Tips

- Start with the oscillators (table position and density) instead of the
  filter. That's where Trinity's character is.
- High density costs the most CPU. If a song is struggling, freeze the Trinity
  clip with the biggest stack.
