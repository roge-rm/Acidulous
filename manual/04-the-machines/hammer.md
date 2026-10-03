# Hammer

> Modelled pianos: felt hammers, strings and a soundboard.

Hammer doesn't play recordings. A felt hammer is thrown at real strings that
ring through a soundboard, so it plays like a piano: harder is brighter as well
as louder, the low notes ring for a long time, and the strings of one key beat
against each other as they fade.

Each key was measured against a recorded concert grand, and **Init** is that
grand.

## Instrument

**model** picks the instrument:

- **grand** - a concert grand, as measured.
- **upright** - shorter strings and a smaller board: less bass, more middle,
  and notes that die sooner.
- **honky-tonk** - an old upright with the strings of each key tuned apart.
- **fortepiano** - a piano from around 1800: light leather hammers, thin
  strings, a quick, clear sound with a knock in it.
- **electric grand** - short strings heard by pickups instead of a board.

The other models are still to come and play the grand for now.

**size** goes from a baby grand to a concert grand: a smaller one has a
thinner, stiffer bass. **age** wears the piano in. The hammers get harder,
the strings ring shorter and darker, and the keys drift out of tune.
**seed** picks which way each key drifts.

## Hammer

- **hardness** - softer felt is darker and rounder, harder felt is brighter.
  **by key** makes the top harder and the bottom softer, or the other way.
- **weight** - a heavier hammer stays on the strings longer, which is darker.
- **strike at** - where on the string the hammer lands. Turned up, it lands
  further in from the end, which is rounder; down is thinner and brighter.
- **velocity** - how much how hard you play changes how loud a note is. The
  blow always follows your playing, so a soft note is still a darker one.
- **tacks** - metal tacks pushed into the felt: every note hard and bright,
  however softly you play. A tack piano.
- **moderator** - a strip of felt between the hammers and the strings: soft
  and muffled.

## Strings

- **sustain** - how long the strings ring. **by key** makes the top ring longer
  and the bottom shorter, or the other way.
- **tone** - how fast the top of the sound dies against the rest. Down is a
  piano going dark as it rings, up keeps it bright. **by key** tilts it across
  the keyboard, like sustain's.
- **stiffness** - how stiff the strings are. Stiff strings put their upper
  partials a little sharp, which is most of what makes a piano sound like a
  piano. **stretch** is how far the tuning follows that, as a piano tuner
  stretches the octaves.
- **strings** - how many strings each key has. **auto** is a grand's: one in
  the lowest notes, then two, then three.
- **unison** - how far apart the strings of one key are tuned. A little gives
  the long, slow aftersound; more is a honky-tonk.
- **couple** - how much the strings talk to each other through the bridge.
- **across** - how much the strings move across the soundboard as well as into
  it, which keeps a note ringing after the first fast fade.

## Pedals

**dampers** is how firmly the felt stops a note when you let go of the key,
and **time** how long that takes. The top keys have no dampers, as on a piano,
so they ring on.

The sustain pedal lifts the dampers, and Hammer follows it part of the way
down: half a pedal lets them touch the strings, which takes the top off a note
and lets the rest ring. **lifts at** is how far down the dampers start to leave
the strings, and **span** how much further they're clear of them.

With the pedal down, strings you didn't play ring along with the ones you did,
an octave or a fifth away. **sympathy** is how much. **noises** is the thump of
the dampers leaving the strings and landing on them again.

The soft pedal is **una corda**: the hammer slides over to miss one string and
meets the others with softer felt, so a note is quieter and darker. The knob
sets how far. On an upright, the hammers move closer to the strings instead,
so they hit more softly.

## Sound

**board** is how loud the soundboard's thump is under each note. **lid** open
is bright; closed takes the top off. **room** is how much of the room you
hear.

**mic** is where you listen from:

- **player** - at the keys, bass on the left.
- **audience** - in front of the piano, the other way round and narrower.
- **close** - in the piano, wide and bright.
- **room** - across the room.

**width** is how wide the keyboard spreads.

## Prepare

Things put on the strings, as a prepared piano has them.

- **what** - **rubber** wedged between the strings mutes most of a note and
  leaves a dull ring. A **screw** puts the partials out of tune, like a bell.
  A **bolt** rattles. **paper** buzzes. **mixed** puts something different on
  each key.
- **keys** - which keys: all, the white or the black ones, the low or high
  half, or some of each at random (the seed picks).
- **where** - how far along the string. Near the end changes less.
- **amount** - how heavy, how loose, how much.

## Out

**volume** and **pan**, and the tuning: **bend**, **octave**,
**transpose** and **fine**.

**voices** is the most notes that ring at once. **detail** at **auto** follows
the quality setting, so a slower phone plays a lighter piano; **full** always
plays the whole model.

## Tips

- A piano's colour is mostly the hammer. Try **hardness** before anything else.
- For an older piano, turn up **age**, or **tone** down and **unison** up a
  little.
- Hold the sustain pedal and play low: the strings ring into each other.
