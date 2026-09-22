# Molt

> Sing a take in, and the piano roll tunes it.

Molt turns a sung recording into an instrument. It finds the individual pulses
of the voice and re-spaces them to change the pitch, so the formants stay where
they were and the voice doesn't go chipmunk.

## The roll does the tuning

Draw the notes you want in the piano roll, sing anything, and the take is pulled
onto those notes.

- **tune** - how hard it's pulled. All the way is hard-tune; less is a gentle
  correction.
- **rate** - how fast it gets there. Slow sounds like a natural slide, fast
  sounds stepped.
- **robot** - flattens the pitch completely onto the note.

## The voice

- **formant** - moves the formants without changing the pitch, so the singer
  sounds bigger or smaller.
- **mega** - a megaphone.
- **start** and **loop** - which part of the take plays, and whether it loops.

Then a filter, an amp envelope and drive.

## Tips

- Keep low rumble out of the recording. Handling noise from holding the phone
  can throw the pitch detection off. The recorder's low cut helps, and it's on
  by default for Molt.
- Consonants are copied rather than pitched, so they stay crisp.
- Singing roughly and letting the roll fix it is a perfectly good way to work.
