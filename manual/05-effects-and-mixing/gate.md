# Gate
> A noise gate, with a filter on what it listens to, and a sidechain.

## The controls

- **threshold** - the level it opens at, -80 to 0 dB.
- **hyst** - how far below the threshold the signal has to drop before it closes,
  up to 24 dB. This stops it flickering on a note that sits near the threshold.
- **attack** - how fast it opens, 0.05 to 50 ms.
- **hold** - how long it stays open after the signal drops, up to 500 ms.
- **release** - how fast it closes once the hold is over.
- **duck** *(extra)* - how far down "closed" is. All the way is a full gate;
  around -12 dB suits drums.
- **key** *(extra)* - a high-pass filter on what the gate listens to (not on the
  sound). Raise it so the gate ignores hum and rumble but still opens for the
  notes.
- **sidechain** - what opens it: **own**, or another track. The **key** filter
  still applies.

There's no mix control, since a half-open gate would just be the noise at half
level.

## Tips

- If it chatters, make **hold** longer before touching release.
- For a guitar with mains hum, set **key** to about 120 Hz.
- Key a sustained pad's gate to the hi-hat track to chop it into a rhythm. Set
  **duck** to about -12 dB so the gaps aren't silent.
- Before an amp it removes the pickup's hiss; after the amp it removes the
  amp's. On an input slot it cleans up the recording itself.
