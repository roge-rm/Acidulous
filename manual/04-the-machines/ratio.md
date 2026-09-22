# Ratio

> Six-operator FM, with a knob that morphs between two algorithms.

Ratio is the FM synth. It has six operators, each a sine wave that can be a
carrier (you hear it) or a modulator (it changes the sound of another). How
they're connected is the algorithm.

## Morphing algorithms

Instead of picking one algorithm, you pick two, `algoa` and `algob`, and
**morph** blends between them. The in-between settings aren't on any standard
algorithm list, and you can automate the morph. A pad can open up from two
operators to six over a few bars.

## The operators

Each of the six has:

- **ratio** - its frequency as a multiple of the note. **fixed** unlinks it from
  the note so it stays at one frequency, which is good for formants.
- **level** - for a modulator, how much FM it adds. For a carrier, how loud it
  is.
- **fb** - feedback into itself. Turn it up to get from a sine towards a saw.
- **A D S R** - its own envelope. On a modulator this shapes the tone, not the
  volume.
- **key** tracking, **fine** tune, **pan** and **mode**.

## Everything else

A filter with its own envelope, three extra envelopes for modulation, three
LFOs that can sync to the tempo, and ten matrix rows (source, second source,
destination, depth) that work like Trinity's.

**snap** keeps ratios on useful values (whole numbers, odd numbers, semitones,
bell partials), and **skew** bends them all away from those values together.

## Tips

- Whole-number ratios (1, 2, 3) sound harmonic. Others (1.41, 3.14) sound like
  bells and metal. Good patches often mix both.
- Give modulators shorter envelopes than carriers. A bright attack decaying into
  a soft body is the classic electric piano.
- Feedback on the top operator of a stack is the cheapest way to add
  brightness.
