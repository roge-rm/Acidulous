# Compressor
> The classic four, a sidechain from any track, and a pump in time with the transport that needs no routing at all.

Threshold, ratio, attack and release, doing exactly what they always do - and then the control that this rack could not otherwise offer.

## The controls

- **threshold** - the level above which it starts working, -60 to 0 dB.
- **ratio** - how much of the excess is removed, 1:1 to 20:1. Past about 10:1 it is a limiter.
- **attack** - how quickly it reacts, 0.1 to 100 ms. Slow lets the transient through first, which is what makes a drum sound bigger rather than flatter.
- **release** - how quickly it lets go, 10 to 1000 ms. Too fast and it breathes on every note; too slow and it never recovers between them.
- **makeup** - level back on, up to 24 dB.
- **pump** *(extra)* - the gain is ducked in time with the transport, at **pumprate**, with the exponential recovery a kick would give it. A pad that breathes against the beat with nothing routed into a sidechain input.
- **pumprate** *(extra)* - the note value the duck happens on: 1/16, 1/8, 1/4 or 1/2.
- **sidechain** - what the compressor listens to: **own** is this track's own sound, or pick another track by name and this one is pushed down whenever *that* one is loud. The other track is heard before its fader and its mute, so a kick pulled down in the mix still ducks as hard, and a muted kick can drive a pump nobody hears.

## Using it well

**Attack is the tone control.** On anything percussive, the attack setting decides whether you are hearing the stick or the shell. Compressing a drum bus with a 20 ms attack makes it hit harder; with a 1 ms attack it makes it quieter.

**Duck the bass under the kick.** On the bass: `sidechain` to the drums, ratio 8:1 or more, attack as fast as it goes, release 80-150 ms, threshold down until the meter moves 6-10 dB on every hit. The low end stops fighting itself, and the kick lands in a hole the bass has just left for it.

**Pump or sidechain.** A real sidechain follows the drums, fills and all, and stops when they stop. **pump** needs nothing routed and never misses a beat, which is what you want on a pad in a track whose kick has not been written yet.

**On the master, less than you think.** A ratio of 2:1 with the threshold set so the meter moves 2-3 dB on the loud parts is glue. Anything more is a decision about the music, not about the mix.
