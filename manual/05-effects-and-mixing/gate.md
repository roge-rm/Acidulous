# Gate
> Shut below a level, with a filter on its own detector so it opens for a pick and not for a room.

The effect a loud amp asks for next. Four of its controls are the ones everybody knows; two are not on a pedal, and they are the reason this one is worth having in front of a high-gain sound.

## The controls

- **threshold** - the level it opens above, -80 to 0 dB.
- **hyst** - how far *below* the threshold the signal has to fall before it will shut, up to 24 dB. Without it a note sitting on the threshold flaps the gate open and closed a hundred times a second; this is the band in which nothing is decided.
- **attack** - how fast it opens, 0.05 to 50 ms.
- **hold** - how long it stays open after the signal drops, up to 500 ms. Every peak over the threshold re-arms it, so a low note re-arms it every cycle and never chatters.
- **release** - how fast it closes once the hold runs out.
- **duck** *(extra)* - how far down "closed" is. All the way is a gate; twelve decibels is what drums want, where silence between hits is a hole and the room going quiet is a tightening.
- **key** *(extra)* - a high-pass on the **detector**, not on the audio. A gate in front of an amp is listening to a pickup that hears mains hum, a room and a hand as well as the string, and all of those are low. Slide `key` up and the gate opens for a pick rather than for a building, while the note it passes keeps its bottom end.

There is deliberately **no mix**: half a gate is the noise at half level.

## Using it well

**Hold does the work, not release.** If a gate is chattering, lengthen the hold before you slow the release. Release only shapes how it closes once it has decided to.

**Key it above the hum.** A long cable into a loud amp picks up mains at 50 or 60 Hz and its harmonics. `key` at 120 Hz ignores all of that and still opens for the lowest note on the instrument, because what opens a gate is the attack, which is full of high frequencies.

**Where you put it matters.** Before the amp it removes the hiss your pickups bring in; after the amp it removes the hiss the amp makes, which is usually far more. On an input slot in the record window it is printed into the take, so the recording itself is quiet.
