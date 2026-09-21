# Amp
> A guitar amplifier as a chain, with a modelled cabinet you can resize continuously.

Not a distortion with a cabinet bolted on. An amp is a *chain* - a preamp that clips lopsidedly, a tone stack whose three controls fight each other, a power stage whose supply sags when you dig in, and a speaker in a box - and it is the chain, in that order, that people recognise. The tone stack sits **between** the two nonlinear stages, so it shapes what the second one distorts.

## The controls

- **drive** and **bias** - the preamp. Bias is the valve sense of the word: at nothing the clipping is symmetrical, and turning it up makes the two halves of the wave behave differently, which is most of what "old and woolly" means.
- **bass**, **mid**, **treble** and **stack** - they interact the way a real passive stack does: **bass and treble up scoops the mid**, and turning either of them back fills it in again. `stack` - us, uk or modern - changes how hard they fight, and also what reaches the first stage, how tightly the two stages are coupled and how much the supply sags, which is far more of the difference between two amps than their tone networks are.
- **presence** - a feedback tilt that makes the **power** stage work harder in the upper mids. It sits before that stage, not after it; after it, it would just be a treble knob.
- **master** - how hard the power stage is pushed. This is the other half of the gain structure: a low drive and a high master is a different sound from the reverse at the same loudness.
- **sag** - how far the supply droops under load. It is what makes an amp feel springy rather than fixed.
- **cab** - the cabinet, or off.
- **size** and **cone** *(extra)* - and these are **continuous**. You can sit between a practice combo, a 4x12 and a bass 8x10, on cabinets that do not exist. That is the thing an impulse response cannot do and the reason this one is modelled rather than sampled.
- **mic**, **edge** and **room** - where the microphone is: on-axis to off, centre of the cone to the rim, and how far back.
- **mix** - wet against dry, for the parallel trick below.

## Using it well

**Gain structure before tone.** Decide `drive` and `master` first - which of the two stages is doing the distorting - and only then touch the stack. Setting the tone against the wrong gain structure means doing it twice.

**Move `edge` before you reach for `treble`.** A speaker's brightness varies enormously between the centre of the cone and the rim, and moving the microphone changes the character rather than just the amount. It is the control that most often fixes a sound people are describing as harsh.

**Parallel amp.** Keep `mix` low on a bass or a synth and the amp adds grit and cabinet colour under the clean signal without taking its bottom end away.

**It is the most oversampled thing in the app** - two nonlinear stages with a cabinet filter after them - so it costs more than any other effect here. One instance is comfortable; a dozen is a decision.
