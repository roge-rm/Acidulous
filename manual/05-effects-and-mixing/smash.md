# Smash
> Three bands, each squashed from above and lifted from below.

## The controls

- **depth** - how much of it is mixed in with the track as it was. A little
  adds density; all of it brings every tail and breath up to the level of
  the hits.
- **time** - scales every band's attack and release together. Slow is
  smooth, fast pumps and grinds.
- **threshold** - the level each band is pushed toward, -48 to 0 dB.
- **down** - how hard anything over the threshold is pushed down.
- **up** *(extra)* - how hard anything under it is pulled up. Hiss and hum
  far below are left down.
- **low**, **mid**, **high** - each band's level after it, ±12 dB. The
  bands split at 300 Hz and 5 kHz.
- **in** - drives it harder, up to +24 dB.
- **gain** - the level out.

At depth 0, or with **up** and **down** both at 0, the track comes through
untouched.

### Beat

- **lowduck**, **midduck**, **highduck** *(extra)* - each band can duck on a
  rhythm of its own: beats, offbeat, 8ths, 16ths, 2 & 4, halves, 3-3-2 or
  dotted. So the lows can duck on the kick and the highs on the offbeats.
  The rhythms count from the song's start in bars of four beats and follow
  the track's swing, and only play while the song does. At **sidechain** the
  band ducks under the track picked in **sidechain** instead: the louder that
  track goes over the threshold, the further the band goes down.
- **sidechain** - the track a band at sidechain ducks under.
- **lowdepth**, **middepth**, **highdepth** - how far each band goes down,
  up to 24 dB.
- **recover** - how long a band takes to come back up, 20 ms to 1 s.

The duck works on the whole band, the track as it was too, so it's as deep
at any **depth**.

## Tips

- On a bass that disappears on a phone speaker, **Loud** brings it up front.
- On a reverb or delay send, **Air** brings the tails up without touching the
  hits.
- Fast **time** with **in** turned up grinds a bass into a snarl.
- On a bass or a pad, **Pump Lows** ducks the lows on every beat with no
  routing. **Seesaw** ducks the lows on the beat and the highs on the
  offbeat.
- The **sidechain** patches, like **Kick Room**, need **sidechain** set to
  the kick's track.
