# Deesser
> Turns down a band when it gets too loud: an s, a cymbal.

Listens to a band of frequencies and turns it down when it goes over the
threshold. Made for the hiss of an s on a voice, and just as good on harsh
cymbals.

## The controls

- **freq** - the middle of the band, 2 to 12 kHz.
- **threshold** - where the band starts to come down.
- **range** - the most it comes down.
- **mode** - **split** turns down only the band. **wide** turns down the whole
  sound, which is gentler on a voice with a lot of air.
- **listen** - plays only the band, so you can find where the s sits.
- **relative** *(extra)* - measures the band against the rest of the sound
  instead of against full level, so a quiet line and a loud one are treated
  the same. The threshold is then how far under the rest the band can come
  up: an s is nearly all band, a vowel has it 20 dB or more down. At -36 it
  is 10 dB, each 1 dB lower allows 1 dB less.
- **gain** - the output level.

### Throw

- **throw** *(extra)* - sends what the deesser takes off to its own echo, so
  each s comes back as a bright trail while the voice stays clean. Turned
  down, the echoes already sent ring out. **listen** keeps it quiet.
- **time** - how far apart the echoes are, on the beat: 1/16, 1/8, dotted
  1/8, 1/4, dotted 1/4 or 1/2. The repeats bounce left and right, so a mono
  track comes out in stereo.
- **feedback** - how long the echoes keep coming back.

## Tips

- Turn on **listen**, move **freq** until the s is loudest, then turn
  **listen** off.
- With **relative** on, the singer can move off the microphone and it still
  catches the same s.
- Draw **throw** up for the last word of a line, the way a dub engineer throws
  a word into the delay: only its esses fly off. Try **Dub Sparkle**.
