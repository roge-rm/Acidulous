# Swell
> Upward compression: the quiet parts come up to meet the loud ones, in one
> band or three.

## The controls

- **floor** - everything louder than this is brought up, -80 to 0 dB.
  Anything more than 12 dB under it is left exactly as it is, so the hiss and
  hum at the bottom of a recording stay where they were.
- **ceiling** - the level everything above the floor is pulled toward, -30 to
  0 dB. A sound already over the ceiling is brought down to it.
- **amount** - how far. At 0 nothing moves, at 1 every sound above the floor
  ends up at the ceiling, and halfway brings it halfway.
- **split** *(extra)* - from one band (0) to three (1), split at 300 Hz and
  5 kHz. Split, a quiet top end or a thin low end comes up on its own, which
  is where the air and the breath come from. The bands add back to exactly
  the input, so splitting never changes a sound that isn't being lifted.
- **release** - how fast it lets go after a loud moment, 5 ms to 2 s. Slow is
  close to normalising and keeps the shape of a phrase. Fast follows every
  hit, and very fast follows the waveform itself and turns into distortion.
- **mix** - the dry sound against the lifted one.
- **gain** - the level out, to make up for how much louder it gets.

It looks a few samples ahead so a sudden loud sound is already turned down
when it arrives. That's 8 samples, under 0.2 ms.

## Tips

- Start at the **Loud** preset on a mix bus and bring **amount** up until the
  quiet parts are where you want them.
- For drums, **Room** brings up everything between the hits. Lower **floor**
  to bring up more of the room.
- For a wall of sound, push **amount** to 1 and **release** under 50 ms, then
  bring **mix** down until the dynamics come back.
- On a reverb or delay send it brings the tails up, so a short reverb sounds
  like a long one.
- Set **ceiling** a few dB under the master limiter's so the two don't fight.
