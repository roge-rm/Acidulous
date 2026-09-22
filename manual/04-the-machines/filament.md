# Filament

> Strings by modelling: plucked, bowed or blown at, and they hear each other.

Filament is a physical model of a string. Not a recording of one and not a filtered noise burst - a delay line with a filtered loop, excited at a point, which is what a string *is*. Everything it can do follows from that.

## The exciter

**exciter** chooses how the string is set going - plucked, struck, bowed, blown, or by **the live input**, which means you can excite a modelled string with a microphone.

- **position** - where along the string it is excited. The same node arithmetic as a struck object: exciting at a node silences that partial, so moving this changes the timbre far more than any filter.
- **hardness** - how sharp the excitation is.
- **grit** and **length** - how noisy and how long it is.

## The string

- **couple**, **detune** and **spread** - a course of two strings, slightly apart, which is what a twelve-string and a piano's middle octaves have in common.
- **damper** - a felt on the string, at a position, which is the una corda pedal and also every muted guitar part.

## The body

Four modes standing in for a box, with **size** moving them together and **bodydamp** widening them - the difference between a guitar and a crate - and **bodymix** for how much of it you hear. There are sympathetic strings too, which ring when the played string excites them.

## Modulation

Two envelopes and two syncable LFOs, into eight matrix rows: a source, a destination and a depth.

Neither envelope is wired to anything by default - they are here to be routed, and the string's own amplitude comes from the exciter rather than from an envelope. The sources include **level**, which is how loudly the string is currently ringing, so the model can be made to respond to itself: damping that comes on as the note decays, or a bow that leans harder the quieter the string gets.

Every destination that matters is in the list - **damping**, **tone**, **position**, **pressure**, **damper**, **tension** and **detune** among them. An LFO on `position` is the hand moving up the string while the note sounds.

## Using it well

**The loop is solved from its own phase.** You do not need to know that, but it is why the tuning holds when you move `body` and `damper` - things that on a naive model would pull the pitch about.

**A string cannot hold DC**, and this one does not, which is why long sustains stay steady instead of drifting into a thump.

**Ringing is a time, not a gain.** If a note is too loud, turn it down; if it rings too long, shorten the decay. Turning the loop gain down to shorten it detunes the string.

**Exciter position is the first control to reach for**, before the filter and before the body.

**Then put something on it.** Position is also a modulation destination, and a slow LFO there does more for a static pluck than any amount of reverb.
