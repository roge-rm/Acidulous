# Cumulus

> Pads by spectrum: you describe the sound's shape and the machine builds it.

Cumulus does not have oscillators in the usual sense. You describe a **spectrum** - which partials, how loud, how wide - and the machine builds a wavetable from it offline, then plays that back. It is the machine for pads, drones and anything that wants to be enormous.

## Why it is built that way

Every partial is given a *bandwidth* rather than a single frequency: a smear of energy around where the harmonic would be. That smear is what makes the sound lush without any chorus, because each partial beats against its own spread rather than against a copy of itself.

Building the table costs real work, so it happens **off the audio thread** when the spectrum settles. Everything from `morph` onwards is live and immediate; the spectrum controls are the ones with a moment's thought behind them.

## The spectrum

- **bandwidth** and **bwscale** - how wide each partial is, and whether the high ones are wider than the low ones. This is the main control and the one that makes it a pad.
- **tilt** - the overall slope from bass to treble.
- **stretch** - pushes the partials away from being whole-number multiples, which is what a piano's top octave and every bell have in common.
- **comb** and **period** - scallops the spectrum, taking out regular slices.
- **vowel** and its amount - bends the spectrum towards a formant.
- **odd** - the balance between odd and even partials: all-odd is a clarinet, all-even is hollow.
- **seed** - the random phases. Changing it gives a different sound with the same description.

## Playing it

**detune**, **spread** and **width** stack copies; **drift**, **driftrate** and **scatter** keep the cloud moving. Then an ordinary filter with its own envelope, an amp envelope, and drive.

## Using it well

**Long attacks and long releases.** This machine is at its best when notes overlap; short ones waste what makes it different.

**Move `morph`, not the spectrum, while playing.** The spectrum rebuilds; morph is instant.

**Stretch a little goes a long way** - a few per cent is a grand piano, a lot is a gong.
