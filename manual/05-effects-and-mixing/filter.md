# Filter
> Low, band or high pass, moved by an LFO, the signal's level or another track.

A resonant filter that can move on its own, in time with the song or with the
sound going through it.

## The controls

- **cutoff** - 20 Hz to 20 kHz.
- **reso** - resonance, up to the edge of self-oscillation.
- **mode** - low pass, band pass or high pass.
- **lforate** *(extra)* - the LFO speed as a note value, so it stays in time:
  1/32 to 8 bars, with the triplets (**T**) and dotted ones (**.**) between.
- **lfodepth** *(extra)* - how far the LFO moves the cutoff. Negative sweeps
  down instead of up.
- **envdepth** *(extra)* - how much the signal's level moves the cutoff.
  Positive opens it when you play harder (auto-wah), and negative closes it.
- **sidechain** - whose level moves it: **own** or another track. With a
  negative **envdepth** and the kick as the source, the filter closes on every
  kick and opens again after.

## Tips

- A negative depth leaves the filter open and makes it dip, which sounds quite
  different from a sweep up.
- Combine a slow LFO with a little envelope so it moves with the song and with
  what's played.
- Resonance and band pass lose level, so use **gain** to make it up.
- For a dubstep wobble, put it after a thick bass and start from a **wobble**
  patch. A wobble talks by changing speed: lock **lforate** on the steps
  (1/4, 1/8, 1/8T, 1/16), or map it to a knob or the mod wheel and play it.
  Distortion after it makes it gnarly. Trinity's **Wobble** and **Wub** bass
  patches have the same thing built in, on LFO 1's **sync**.
