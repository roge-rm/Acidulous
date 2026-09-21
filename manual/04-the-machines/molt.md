# Molt

> A voice you write for: sing a take in, and the piano roll tunes it.

Molt takes a sung recording and makes an instrument of it. Not a sampler - it finds the **glottal pulses** in the take, the individual openings of the vocal folds, and lays them down again at a new spacing. Moving the spacing moves the pitch; leaving the grain's own shape alone leaves the formants where they were, so the voice does not turn into a chipmunk.

## The extra: the roll does the tuning

This is the machine's whole idea. **Draw the notes you want in the piano roll, sing anything, and the take is pulled onto those notes.** The melody comes from the clip and not from a knob.

- **tune** - how hard it is pulled. All the way is hard-tune, part of the way is a correction.
- **rate** - how quickly it gets there, which is the difference between a natural slide and the stepped sound.
- **robot** - flattens the pitch entirely onto the note.

## The voice

- **formant** - moves the formants independently of the pitch, which is the size of the singer rather than their note.
- **mega** - a megaphone.
- **start** and **loop** - which part of the take is used, and whether it repeats.

Then an ordinary filter, an amp envelope and drive.

## Using it well

**Take the rumble out first.** The recording page's low cut exists for this: a hand holding a phone puts more than half the take's energy below seventy hertz, the pitch marks snap to *that* instead of the voice, and the whole machine comes apart. It is done for you on the way in, but a bad recording is still a bad recording.

**Consonants are copied, not stretched.** The analysis knows which parts have a pitch and which do not, so the sibilants stay sharp instead of being smeared onto a note - which is exactly what makes a cheap pitch shifter sound cheap.

**Sing flat and let the roll fix it** is a legitimate way to work here, and much faster than singing it right.
