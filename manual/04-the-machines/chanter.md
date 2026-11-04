# Chanter

> Modelled bagpipes and hurdy-gurdy: drones that go on between notes and
> can follow the song's bass, a bag that runs out, grace notes, and a wheel
> whose loose bridge buzzes in time.

Chanter doesn't play recordings. A bagpipe here is a chanter, a conical
pipe with a double reed (the same model as Timber's), and drones, narrow
pipes with single reeds, all blown from a bag. The bag keeps the pressure
up, so the drones carry on between notes and for a while after the last
one. A chanter can't be tongued, so pipers set notes apart with grace
notes: a quick, high note before each one.

A hurdy-gurdy is a rosined wheel that bows its strings for as long as it
turns: melody strings stopped by the keys, drone strings tuned to the key,
and the trompette, whose bridge (the dog) sits loose, so once the wheel
turns fast enough it hops and buzzes against the soundboard. Players push
the wheel on the beat to make it buzz in rhythm.

## Pipes

- **model** - **highland** pipes (two tenor drones on the key and a bass
  an octave down), **smallpipes** (a quiet, closed chanter that stops for a
  moment between notes, drones on the key, the fifth and the octave),
  **gaita** (one bass drone and a sweet chanter), **uilleann** pipes
  (sweet and closed; a key held under the melody sounds on the
  regulators), **gaida** (one deep drone, a bright chanter),
  **cornemuse**, **musette** (small and soft), **säckpipa**, **dudy**
  (one long drone two octaves down), **zampogna**, **tulum** or
  **launeddas** (two chanters: hold a second key and the other hand plays
  it), or **hurdy-gurdy**.
- **tune** - in cents.
- **tonic** - the key the drones are tuned to.
- **drones** - how loud the drones are.
- **reed** - a soft or hard chanter reed: darker or brighter.
- **grace** - a quick high note before each note, in milliseconds. At 0,
  none.
- **drift** - how much the bag's pressure wanders as it's squeezed.
- **air** - breath in the reeds.

## Song

The drones can follow another track's bass.

- **from track** - the track to listen to, or **off**. Pick the bass, or a
  group with the bass in it. Loading a patch keeps the track.
- **follow** - how quickly the drones move to the bass note the track
  leans on. At 0 they stay on the tonic. Low, a note has to hold for
  about a second before they go to it, so passing notes don't drag them;
  at the top they follow almost every note. They keep their intervals (a
  bass drone stays an octave down) and stay near the tonic's octave. In a
  tuning they take the tuning's notes.
- **glide** - how long a move takes, in seconds. Pipe drones slide over
  like a reed being pulled round, and dip a little on the way. The
  hurdy-gurdy's drone strings and trompette are retuned evenly, like a peg
  turned. At 0 they jump.

## Bag

- **bag** - how many seconds the piper keeps blowing after the last key
  is let go, so the drones go on. On the hurdy-gurdy, how long the wheel
  keeps being cranked.
- **wind** - how much of what the pipes use the piper's breath puts back.
  At 1 the bag never runs out. At 0.5 it just keeps up with playing,
  rests fill it up again, and under that a long tune drains it: the
  pressure holds until the bag goes soft, then sags, the chanter goes thin
  and the drones choke one by one. Let go and what's left is squeezed out
  through the drones. On the hurdy-gurdy it's the arm on the crank: the
  wheel slows over a long tune and coasts down when it's left. The
  launeddas have no bag, so wind sets how often the player breathes in
  through the nose, with a dip in the pressure each time.
- **sag** - how far the pitch falls as the pressure goes. As the bag
  empties the chanter goes flat and each drone falls further as it dies,
  the groan pipes make. With wind at 1, sag still gives you that groan as
  the drones run down after the last note.

## Hurdy-gurdy

- **crank** - how fast the hurdy-gurdy's wheel turns. Faster is louder.
- **rosin** - how hard the rosin grips the strings: smooth or gritty.
- **dog** - how loud the trompette's buzz is.
- **threshold** - how fast the wheel must turn before the dog buzzes.
- **coup** - pushes the wheel **1**, **2** or **4** times a beat, in time
  with the song, so the dog buzzes in rhythm. **off** for none.
- **beats** - **every** beat, or only on **2 & 4** of the bar (the even
  beats in other signatures). With the song stopped it counts from the first
  note.

## Player

- **velocity**, **octave**, **bend** and **volume**.

Pressure, or the mod wheel, squeezes the bag (a little louder and sharper,
and with a finite bag it empties faster) or pushes the hurdy-gurdy's
wheel, so you can make the dog buzz by hand.

## Macros

The **play** page has eight macro knobs. A patch can name them (Drones,
Squeeze, etc.) and the names show on the knobs, on automation lanes and in
the controller mapping list. They sit at zero when a patch loads, which is
the patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch moves the notes played after it, 24 semitones at 1.
The model, tune, tonic, coup, beats, from track, glide, octave and bend can't be reached. A change to
the grace or velocity is heard from the next note; the rest move at once.
Tap a macro's name to rename it.

## Tips

- Both play one note at a time: the last key held sounds.
- Hold a note on a **drones** preset and let go: the drones carry on.
- The **song** presets follow track 1's bass; set **from track** to your
  bass track. The **bag** presets run dry on purpose: play a long tune and
  let go at the end.
- On **Hurdy-Gurdy**, lean on a key or push the mod wheel to buzz.
