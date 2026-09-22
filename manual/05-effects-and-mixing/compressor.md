# Compressor
> The usual controls, a sidechain from any track, and a tempo-synced pump.

## The controls

- **threshold** - the level it starts working above, -60 to 0 dB.
- **ratio** - how hard it compresses, 1:1 to 20:1. Above about 10:1 it acts as a
  limiter.
- **attack** - how fast it reacts, 0.1 to 100 ms. Slower lets the start of each
  hit through, which makes drums punchier.
- **release** - how fast it lets go, 10 to 1000 ms.
- **makeup** - adds level back, up to 24 dB.
- **pump** *(extra)* - ducks the sound in time with the song, like a sidechain
  from a kick that isn't there.
- **pumprate** *(extra)* - how often it pumps: 1/16, 1/8, 1/4 or 1/2.
- **sidechain** - what the compressor listens to: **own** (this track), or
  another track. With another track, this one gets pushed down whenever that
  track is loud. It hears the other track before its fader and mute, so a
  muted kick still works as a trigger.

## Tips

- On drums, **attack** changes the tone: around 20 ms is punchier, 1 ms is
  flatter.
- **Duck the bass under the kick**: on the bass, set **sidechain** to the drums,
  ratio 8:1 or more, the fastest attack, release 80-150 ms, and lower the
  threshold until it ducks 6-10 dB on each kick.
- A real sidechain follows the actual drums, fills included. **pump** doesn't
  need routing and never misses a beat, which is handy before the drums are
  written.
- On the master, go gentle: 2:1, with 2-3 dB of reduction on the loud parts.
