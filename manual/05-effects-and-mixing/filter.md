# Filter
> Low, band or high pass, moved by an LFO, the signal's level, or another track.

A resonant filter that can move on its own, in time with the song or with the
sound going through it.

## The controls

- **cutoff** - 20 Hz to 20 kHz.
- **reso** - resonance, up to the edge of self-oscillation.
- **mode** - low pass, band pass or high pass.
- **lforate** *(extra)* - the LFO speed as a note value, so it stays in time.
- **lfodepth** *(extra)* - how far the LFO moves the cutoff. Negative sweeps
  down instead of up.
- **envdepth** *(extra)* - how much the signal's level moves the cutoff.
  Positive opens it when you play harder (auto-wah); negative closes it.
- **sidechain** - whose level moves it: **own**, or another track. With a
  negative **envdepth** and the kick as the source, the filter closes on every
  kick and opens again after.

## Tips

- A negative depth lets you leave the filter open and have it dip, which sounds
  quite different from a sweep up.
- Combine a slow LFO with a little envelope so it moves with the song and with
  what's played.
- Resonance and band pass lose level. Use **gain** to make it up.
