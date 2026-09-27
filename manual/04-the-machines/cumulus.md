# Cumulus

> Pads built from a spectrum of partials.

Cumulus doesn't use normal oscillators. You describe a spectrum (which
partials, how loud, how wide), it builds a wavetable from that and plays it.
It's for pads, drones and huge sounds.

## How it works

Each partial has a width instead of being a single frequency, which makes it
lush without needing a chorus.

Building the table takes a moment, so it happens in the background whenever you
change the spectrum. Everything from **morph** on changes straight away.

## Spectrum controls

- **bandwidth** and **bwscale** - how wide each partial is, and whether the high
  ones are wider than the low ones. This is the main control.
- **tilt** - the balance of bass to treble.
- **stretch** - pushes the partials off whole-number multiples, like a piano's
  top end or a bell. A little goes a long way.
- **comb** and **every** - cut regular notches in the spectrum.
- **vowel** and its amount - shape the spectrum towards a vowel.
- **odd** - odd against even partials. All odd sounds like a clarinet, and all
  even sounds hollow.
- **seed** - the random phases. Change it for a different take on the same
  sound.

## Playing controls

**detune**, **spread** and **width** stack copies, and **drift**,
**driftrate** and **scatter** keep the sound moving. Then there's a filter with
its own envelope, an amp envelope and drive.

## Tips

- Use long attacks and releases and let the notes overlap.
- Move **morph** while playing instead of the spectrum controls, since morph
  changes straight away.
