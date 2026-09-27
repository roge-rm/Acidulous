# Ratio

> Six-operator FM, with a knob that morphs between two algorithms.

Ratio is the FM synth. It has six operators, each a sine wave that's either a
carrier (you hear it) or a modulator (it changes the sound of another). How
they're connected is the algorithm.

## Morphing algorithms

You pick two algorithms, `algoa` and `algob`, and **morph** blends between
them. The in-between settings aren't on any standard algorithm list, and you
can automate the morph, so a pad can open up from two operators to six over a
few bars.

## The operators

Each of the six has:

- **ratio** - its frequency as a multiple of the note. **fixed** holds it at one
  frequency instead, which is good for formants.
- **level** - how much FM it adds as a modulator, or how loud it is as a
  carrier.
- **fb** - feedback into itself, from a sine towards a saw.
- **A D S R** - its own envelope. On a modulator this shapes the tone instead of
  the volume.
- **key** tracking, **fine** tune, **pan** and **mode**.

## Everything else

A filter with its own envelope, three extra envelopes for modulation, three
LFOs that can sync to the tempo and ten matrix rows (source, second source,
destination, depth) that work like Trinity's.

**snap** keeps ratios on useful values (whole numbers, odd numbers, semitones,
bell partials), and **skew** bends them all away from those values together.

## Tips

- Whole-number ratios (1, 2, 3) sound harmonic, and others (1.41, 3.14) sound
  like bells and metal. Good patches often mix both.
- Give modulators shorter envelopes than carriers. A bright attack fading into
  a soft body is the classic electric piano.
- Feedback on the top operator of a stack is the cheapest way to add
  brightness.
