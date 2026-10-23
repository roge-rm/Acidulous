# Aviary

> Modelled birdsong: a syrinx with two voices, a throat and a beak, singing
> whistles, chirps, trills, warbles, calls, coos and caws in time with the
> song.

Aviary doesn't play recordings. A bird sings from its syrinx, deep in its
chest: two sources, one in each branch of its airway, each a pair of folds
that start to swing once the air pressure passes a threshold and settle at
a size set by how far past it the pressure goes. The two can sing at once,
so one bird can sing two notes. Above them are a short trachea, a throat
the bird tunes to the note it's singing, and the beak, opened wider for
higher notes.

Hold a note and a bird sings a song around it, two octaves up, in time with
the tempo. Let go and it stops.

## Song

- **pattern** - **whistle** (one held note, sliding into it), **chirp**
  (a syllable that sweeps, then a gap), **trill** (fast short syllables),
  **warble** (phrases of quick falls, rises, slurs, buzzes and short trills,
  each sung once or a few times, then a pause), **call** (two notes a third
  apart, the rest of the flock answering in the gaps), **chorus** (different
  birds near and far, each with its own song, pitch and pace, mostly taking
  turns), **coo** (three to five slow notes, each lower than the last, the
  last one held, then a rest) or **caw** (short harsh calls in twos and
  threes, then a rest; a flock of them calls at its own times, from near and
  far).
- **tune** - in cents.
- **rate** - syllables a beat: **1**, **2**, **4**, **8** or **16**.
- **length** - how much of each syllable's slot is sung.
- **sweep** - how far each syllable slides, in semitones: falling from above,
  or rising from below when it's negative. In a coo it's how far the notes
  fall, and in a caw how far each call rises and drops.

## Voice

- **rasp** - the folds slapping shut: harsher, with more overtones.
- **two** - the syrinx's other side singing at once.
- **interval** - how far above (or below) the other side sings.
- **breath** - air in the sound.
- **throat** - how much the throat, tuned to the note, rings.
- **beak** - how wide the beak opens: wider is brighter.

## Flock

- **flock** - how many birds sing each note.
- **spread** - how far apart they sit, left to right.
- **space** - how far off they are: quieter and duller.
- **velocity**, **octave**, **bend** and **volume**.

Pressure blows harder: louder and fuller. The mod wheel widens the sweeps.

## Kit

Turn on **kit** and the keys from C2 up are sixteen pads, each its own bird, so one track can play a whole hedge: a whistle on one pad, a trill on another, a pair calling back and forth on a third. The sequencer shows the drum grid, one lane per pad.

- Tap a pad on the drum grid, then set it in the pad card: **pattern**, **pitch**, **birds** and **level**.
- The other knobs (rate, sweep, rasp and so on) work on every pad.
- **Bird Kit** starts with whistles, chirps, trills, warbles, calls and choruses.

## Macros

The **play** page has eight macro knobs. A patch can name them (Harsh,
Far, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves every bird, 24 semitones at 1. The pattern, tune,
rate, flock, octave, bend and a kit's pads can't be reached. In a kit the
routes move the knobs every pad shares. A change to the sweep is heard from
the next syllable and to the spread from the next note; the rest move at
once. Tap a macro's name to rename it.

## Tips

- The patterns follow the tempo, so a chirp at **4** a beat lands on the
  sixteenths.
- **Call and Answer** needs at least two birds: one calls, the other
  answers.
- A coo is sung with the beak shut and a caw with the rasp turned up, whatever
  the rasp knob says. For a dove or an owl try **octave** -1 and the
  **throat** up; for a crow, **octave** -1 and more **breath**.
- With more than one bird, the others in a coo or caw come in a phrase
  later, one after another.
