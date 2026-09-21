# Brazen

> Brass by modelling: lips blown open against a tube.

Brazen is a model of a brass instrument. A pair of lips, a length of tube, a bell at the end - and the note is what happens when the pressure behind the lips and the standing wave in the tube agree with each other.

## Blowing it

- **pressure** - how hard you blow, and the single most important control. Below a threshold nothing sounds; past it the note starts; past that again it gets brighter and eventually overblows. It is not a volume knob.
- **breath** - noise in the air stream.
- **bite** and **tension** - the lips: how tight and how damped. Tension against pressure is what decides which harmonic of the tube you land on, which is a real brass player's embouchure written as two numbers.
- **lipdamp** - the lip resonance's damping, with a floor under it. Without that floor the model can find a solution with no damping at all and screams.

## The tube

- **size** - how long, from a trumpet to a tuba.
- **bore** and **bell** - how the tube flares, which decides how much of the high end escapes rather than reflecting back.
- **loss** - how much is lost per round trip, stated per trip rather than per second so it means the same thing at every length.
- **brass** - the nonlinearity that makes brass brassy: at high pressure the wave steepens on its way down the tube, and that steepening is the blare.

## A section

**lock** and **drift** turn one player into several who are nearly together: each copy drifts on its own and locks loosely to the others, which is what a section is. **growl** and **growlrate** are the flutter of a player growling into the mouthpiece.

## Using it well

**A tuba is blown hard.** Big instruments need *more* pressure, not less - the model behaves like the real thing, which surprises people.

**Automate pressure across a note.** A swell is a pressure move, and it changes the timbre as it goes, which is the thing a volume fade cannot do.

**Start from a patch and move `size` first** - the whole family is one control apart.
