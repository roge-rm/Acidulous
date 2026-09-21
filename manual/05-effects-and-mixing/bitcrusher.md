# Bitcrusher
> Fewer bits and a lower rate, with a jitter that makes the clock unsteady.

Two separate kinds of damage that usually get one knob between them: **bits** throws away resolution, **rate** throws away time. They sound nothing alike and doing them independently is most of the point.

## The controls

- **bits** - 16 down to 1. Reducing resolution adds a noise floor that follows the signal, so quiet passages get dirtier rather than the reverse.
- **rate** - the sample-and-hold rate, 48 kHz down to 500 Hz. This is the one that adds the metallic ring, because everything above half the new rate folds back down to somewhere it does not belong.
- **jitter** *(extra)* - the hold clock is not steady. Real early samplers had unstable clocks and it is why they sound like they do rather than like a clean decimator.
- **tone** *(extra)* - a low-pass after the damage, so you can have the grit without all of the fizz.
- **mix** - wet against dry.

## Using it well

**Rate first, bits second.** Most of what people recognise as "lo-fi" is the folding from a low sample rate. Bits on their own mostly add hiss.

**A little jitter is worth a lot of rate.** At a moderate rate, a small amount of jitter smears the folded partials into something that sounds like a machine rather than a calculation.

**Tone makes it usable on a whole mix.** Crushing a send and rolling the tone back to 4 kHz gives you a second, ruined copy of the song sitting behind the clean one; without the tone control the same thing is unbearable.
