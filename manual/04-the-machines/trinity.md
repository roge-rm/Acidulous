# Trinity

> Three oscillators, wavetables, two filters and a mod matrix: the all-rounder.

Trinity is the general-purpose poly synth, three oscillators into two filters
with a modulation matrix on top.

## Oscillators

Each oscillator plays a wavetable, a row of waveshapes that **pos** moves
through. Automate **pos** to sweep the tone.

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

The mod wheel also opens both filters, whatever the matrix says, and **wheel**
in the voice section sets how much. At zero the wheel only does what the matrix
tells it to.

## Tips

- Start with the oscillators (table position and density) instead of the
  filter. That's where Trinity's character is.
- High density costs the most CPU. If a song is struggling, freeze the Trinity
  clip with the biggest stack.
