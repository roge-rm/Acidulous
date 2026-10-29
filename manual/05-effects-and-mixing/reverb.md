# Reverb
> Twelve rooms and things that ring, with a tail you can duck, push back or freeze.

A reverb in twelve models. **model** picks one, and the panel shows the knobs
every model has plus the chosen model's own.

## The models

- **room** - the original reverb, a plain room.
- **plate** - a metal plate: bright and dense.
- **hall** - a big hall with early reflections, the lows lasting longer than
  the highs.
- **spring** - the spring tank in a guitar amp. Every echo comes back as a
  chirp.
  - **drip** - how far each echo chirps.
  - **knock** - how hard a sharp hit shakes the tank into a boing.
- **early** - an early digital reverb: short lines at a low sample rate,
  grainy and metallic.
  - **rate** - the rate the tank runs at, 8 to 32 kHz. Lower is darker and
    grittier.
- **space** - a room built from its size in metres. It has no **size** knob.
  - **width**, **depth** and **height** - the room, from 2 m up to 30, 50 and
    15 m.
  - **position** - where you stand, from the front to the back.
  - **material** - the walls: tile, plaster, wood or curtains.
- **tube** - a tuned pipe the track rings through, closed at one end.
  - **pitch** - in semitones from middle C.
  - **follow** - tunes to the track's last note instead. Only on a track's own
    inserts, since groups, sends, the master and the input get no notes.
  - **open** - from a pipe closed at one end (odd harmonics) to one open at
    both (all of them).
- **string** - a tuned string the track rings through, with **pitch** and
  **follow** as on the tube.
  - **stiff** - pulls the overtones sharp, from a string to a metal wire.
  - **pick** - where the sound lands on the string, from the end to the
    middle.
- **drumhead** - a drum skin the track hits. **size** is the drum.
  - **tension** - tunes the head.
  - **snares** - wires under the head that buzz when a hit moves it far
    enough, so a kick sets them off and a quiet note doesn't.
- **thunder** - a metal sheet. A soft hit hums and a hard one blooms into a
  crash after the strike.
  - **metal** - how readily it goes to the crash, from a heavy gong to a thin
    sheet.
- **howl** - a room with a microphone in feedback.
  - **howl** - turned up, the room's notes ring longer, then sing and hold, so
    anything you play turns into drones. **size** retunes them.
- **flutter** - two parallel walls, like a stairwell or a tunnel. The echo
  bouncing between them rings as a pitch.
  - **walls** - how far apart they are, from a quarter metre (a high ring) to
    20 m (a slow flutter).

## On every model

- **size** - how big the space is, or how long it rings. Space has no size.
- **damp** - how absorbent the room is. Damped sounds like carpet and undamped
  like tiles.
- **tone** - a low-pass on the reverb tail.
- **predelay** - a gap before the reverb starts, up to 200 ms.
- **mix** - wet against dry.
- **duck** - drops the tail while the track plays and brings it back in the
  gaps. **duckrel** is how fast it comes back, 50 ms to 2 s.
- **distance** - moves the sound further away: a longer predelay, quieter and
  later early reflections, more tail against the dry sound, a duller tail, and
  a narrower dry sound with a wider tail.
- **bloom** - the tail starts thin and fills in over up to two seconds, and
  falls back when the sound stops.
- **freeze** *(extra)* - holds the tail forever.
- **gate** *(extra)* - cuts the tail off sharply, for the classic 80s gated
  snare.
- **shimmer** *(extra)* - feeds the tail back an octave up, so a held chord keeps
  rising.
- **bits** and **crush** *(extra)* - bit reduction and sample-rate reduction on
  the tail only.
- **wobble** *(extra)* - makes the tail drift a little so it's never quite
  still.

## Tips

- If the reverb makes things muddy, try 30-60 ms of **predelay** or some
  **duck** before making it smaller.
- Shimmer works best with a low mix and a long tail.
- Put **bits** and **crush** on a send for a lo-fi copy of the mix behind the
  clean one.
- The tube and string with **follow** on ring along with the part. Try them
  on drums with **pitch** set to the song's key.
