# Delay
> Echoes on a note value, in four modes, and it can duck while you play.

A delay timed to the song. **time** is a note value, so a dotted-eighth delay
stays a dotted eighth when the tempo changes.

## The controls

- **time** - the gap between echoes, from 1/32 to a whole bar, including dotted
  values.
- **feedback** - how much of each echo is fed back in, up to 0.95.
- **tone** - a low-pass on the echoes, so each repeat is duller than the last.
- **mode** - how the echoes come back:
  - **normal** - an echo on each side.
  - **ping-pong** - the echoes bounce between left and right.
  - **multi-tap** - two to four echoes inside the time, spread from left to
    right. Only the last one feeds back.
  - **reverse** - each stretch of **time** is played backwards. With feedback,
    the next pass plays forwards again.
- **taps** - how many echoes multi-tap puts inside the time, 2 to 4.
- **spacing** - where the taps sit: in the middle they're even, down bunches
  them early and up bunches them late, toward the full time.
- **mix** - how much of the echo you hear.
- **duck** *(extra)* - turns the echoes down while the dry sound plays and
  brings them back in the gaps.
- **wobble** *(extra)* - makes the delay time drift slowly like a tape machine.
  A little warms it up, and more detunes the echoes.

## Tips

- Bring **tone** down to a few kHz so long feedback fades into the background
  instead of piling up.
- Use **duck** instead of lowering the mix to stop the delay swamping a part.
- Reverse on a quarter or a half note swells into each beat. A reverse
  stretch starts on the song's beat, so it's the same wherever play starts.
  One longer than 3 seconds (a bar below 80) plays back in halves.
- Three even taps in a quarter is a triplet; four taps over a half note
  repeats every eighth.
- On a send the delay is always fully wet, and each track's send slider sets how
  much goes to it.
