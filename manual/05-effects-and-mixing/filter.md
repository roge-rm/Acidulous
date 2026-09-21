# Filter
> Low, band or high pass, swept by an LFO on a note value or by the signal's own envelope.

A state-variable filter with resonance, and two ways of moving it that do not need an automation lane: in time with the transport, or in time with the part.

## The controls

- **cutoff** - where it turns over, 20 Hz to 20 kHz.
- **reso** - emphasis at the corner, up to the edge of self-oscillation.
- **mode** - low pass, band pass or high pass.
- **lforate** *(extra)* - a note value rather than a frequency, so the sweep is locked to the song and stays locked when the tempo moves.
- **lfodepth** *(extra)* - how far the LFO moves the cutoff, and **signed**: negative sweeps down from where you set it rather than up.
- **envdepth** *(extra)* - how far the signal's own level moves the cutoff. Positive is an auto-wah that opens when you play harder; negative closes instead, which is the sound nothing else here makes.

## Using it well

**A signed depth is two effects.** Most filters give you a sweep upward and expect you to set the cutoff low. Being able to go the other way means the resting position can be open and the movement can be a dip, which reads as a very different thing.

**Envelope and LFO together.** Set a slow `lforate` for the bar-level movement and a little `envdepth` on top, and the filter breathes with the song *and* responds to what is played into it. Either alone sounds mechanical by comparison.

**Resonance costs level.** A band pass in particular throws away most of the signal; the `gain` trim at the end is there for exactly that and is not cheating.
