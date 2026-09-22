# Ratio

> Six-operator FM, with the algorithm itself on a knob.

Ratio is the FM machine. Six operators, each a sine that can be a carrier or a modulator, and the arrangement of them - the algorithm - is what makes FM sound like FM.

## The extra: the algorithm morphs

Classic FM gives you a numbered list of algorithms and you pick one. Ratio gives you **two** - `algoa` and `algob` - and a **morph** knob between them. Halfway between a stack and a pair of parallel carriers is an arrangement no algorithm list contains, and it is a knob you can automate.

This is the machine's whole reason for existing. A pad that opens from two operators to six over eight bars is one lane, not a patch change.

## The operators

Each of the six has:

- **ratio**, its frequency as a multiple of the note - and **fixed**, which unpins it from the note so it stays where it is put. Fixed operators are how you get a formant that does not move as you play up the keyboard.
- **level**, which for a modulator is the index - how much FM it applies - and for a carrier is how loud it is.
- **fb**, feedback into itself, which is how a sine becomes a saw.
- its own **A D S R**, because in FM the envelope on a modulator is the timbre and not the volume.
- **key** tracking, **fine**, **pan** and **mode**.

## Around them

A filter with its own envelope, three more envelopes over and above the six the operators carry, three syncable LFOs, and ten matrix rows in the same shape as Trinity's - source, a second source that scales it, destination, depth.

The three spare envelopes are for routing and nothing else, so a ratio that walks over the first second of a note is one row.

## Using it well

**Ratios that are whole numbers are harmonic; ones that are not are metallic.** 1, 2 and 3 give you pitched sounds. 1.41 and 3.14 give you bells and clangs. The interesting patches usually have both.

**Modulator envelopes should be shorter than carrier envelopes.** That is what makes an FM electric piano: a bright attack that decays into a soft body, which is one operator's decay set short.

**Feedback on the last operator in a stack is the cheapest brightness there is** - reach for it before adding another operator.
