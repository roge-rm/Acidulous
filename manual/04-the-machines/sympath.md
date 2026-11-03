# Sympath

> Modelled sitar, tanpura, veena, shamisen and their relatives, plucked or
> bowed: strings over a buzzing bridge, with strings ringing in sympathy.

Sympath doesn't play recordings. Each note is a string lying over a wide,
curved bridge. Once the string swings far enough towards the bridge it
strikes it, harder the further it swings, and every strike sets the bridge
ringing: the bright buzz on every cycle that goes on as long as the string
swings wide enough to reach, and fades as the note dies down.

Under the played strings lie sympathetic strings that are never touched.
They're tuned to the scale of the piece, so any note that lands on one of
theirs sets it ringing, and it rings on after the note has gone.

The rest of the song can ring them too. Pick another track and its sound
shakes the sympathetic and drone strings, so they ring under whatever that
track plays, even with nothing played on Sympath. Turn up **listen** and they
retune themselves to the notes that track plays.

## Strings

- **model** - **sitar** (eleven sympathetic strings and two drone strings),
  **tanpura** (four strings it plucks by itself), **veena** (a flatter
  bridge and three drone strings at the side) or **shamisen** (a big
  plectrum that hits the skin too, and its low string buzzing on the neck).
  Plucked too: **sarod** (no frets, a steel plate under the strings),
  **rudra veena** (deep and slow), **swarmandal** (a zither), **biwa**,
  **sanxian**, **ektara** (one string) and **e-sitar** (an electric sitar).
  Bowed, for as long as the key is held: **sarangi**, **esraj**, **dilruba**,
  **hardanger** fiddle and **nyckelharpa**.
- **tune** - in cents.
- **sa** - the tonic the sympathetic and drone strings are tuned to.
- **scale** - the scale the sympathetic strings are tuned to: **bilawal**
  (major), **khamaj**, **kafi**, **asavari**, **bhairavi**, **bhairav**,
  **kalyan**, **marwa**, **purvi** or **todi**.
- **bridge** - how much the bridge buzzes. At 0 it's a plain, sharp edge.
- **curve** - how wide the string must swing before it lies on the bridge.
  Low, and even soft notes buzz; high, and only hard ones do.
- **pluck** - a soft or hard pluck. On a bowed one, how hard the bow presses.
- **position** - where along the string it's plucked.
- **sustain** - how long the strings ring.
- **bright** - how long they keep their top end.

## Drones

- **tarbs** - how loud the sympathetic strings ring.
- **chikari** - strikes the drone strings with each new note.
- **bloom** - how long the sympathetic and drone strings ring, from a
  quarter as long to four times as long. They ring about as loud either way.
- **first** - the tanpura's first string: **pa**, **ma**, **ni** or **sa**
  an octave down. The other three are Sa, Sa and Sa an octave down.
- **cycle** - how many beats the tanpura takes to pluck its four strings
  and start again: **2**, **4**, **6** or **8**. It follows the tempo.

## Song

- **from track** - the track whose sound rings the sympathetic and drone
  strings, or **off**. Only models with sympathetic or drone strings can be
  rung: sitar, shamisen, sarod, the veenas, e-sitar and the bowed ones.
- **ring** - how hard that track shakes them.
- **listen** - at 0 the strings stay on **sa** and **scale**. Turn it up and
  they retune to the notes the track plays, gliding to each new set. A
  little learns the song's key over several bars; all the way follows each
  chord. The drone strings settle on the note the track leans on most.

The strings follow the track's tuning. In a tuning with more than twelve
notes an octave, **scale** takes the nearest steps of the tuning, and
**listen** hears the tuning's own steps.

## Hands

- **meend** - with one voice, a new note slides from the last along the
  same string instead of being plucked again.
- **gamak** - shakes the note quickly. The mod wheel does the same.
- **voices**, **velocity**, **octave**, **bend** and **volume**.

Pressure (from the keyboard or each note's own, with MPE) pulls the string
sideways, bending the note up by as much as a whole tone.

## Macros

The **play** page has eight macro knobs. A patch can name them (Buzz, Tarbs,
etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves the notes played after it, 24 semitones at 1. The
model, tune, sa, scale, first, cycle, voices, octave, bend and the track can't
be reached.
A change to the pluck, position, chikari or velocity is heard from the next
note; the rest move at once, and so does the pluck on a bowed one. Tap a
macro's name to rename it.

## Tips

- Set **sa** to the key of the song and **scale** to its scale, and the
  sympathetic strings ring along with the tune.
- Put Sympath on a track with no clips, point **from track** at the drums or
  the band and turn **listen** up: the song gets a halo of strings in its own
  key. The **song** patches point at track 1.
- The tanpura only plucks while a note is held: hold Sa for as long as the
  drone should go.
- **Sitar** has one voice: play legato for meend, and press harder to pull
  a note up.
