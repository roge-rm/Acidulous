# Reverb
> A room that can also freeze, gate, shimmer, or crush itself.

A reverb with the usual size, damping and predelay, plus some extras.

## The controls

- **size** - how big the space is.
- **damp** - how absorbent the room is. Damped sounds like carpet, undamped like
  tiles.
- **tone** - a low-pass on the reverb tail.
- **predelay** - a gap before the reverb starts, up to 200 ms.
- **mix** - wet against dry.
- **freeze** *(extra)* - holds the tail forever.
- **gate** *(extra)* - cuts the tail off sharply, for the classic 80s gated
  snare.
- **shimmer** *(extra)* - feeds the tail back an octave up, so a held chord keeps
  rising.
- **bits** and **crush** *(extra)* - bit reduction and sample-rate reduction on
  the tail only.
- **wobble** *(extra)* - makes the tail drift slightly so it's never quite
  still.

## Tips

- If the reverb makes things muddy, try 30-60 ms of **predelay** before making
  it smaller.
- Shimmer works best with a low mix and a long tail.
- Put **bits** and **crush** on a send for a lo-fi copy of the mix behind the
  clean one.
