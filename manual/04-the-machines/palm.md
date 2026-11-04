# Palm

> Modelled hand drums: tabla and bayan, djembe, cajón, frame drum,
> talking drum, congas, bongos, darbuka, riq, tar, bendir, kanjira, batá,
> mridangam, dholak, ashiko, udu and cuíca, played with open, slap, muted, bass and rim strokes.

Palm doesn't play recordings. Each note is a drum head tuned to the note. A
plain head rings at the uneven ratios of a stretched circle, which is why
most drums have no clear pitch; a tabla's head is loaded with a paste in the
middle that pulls its overtones into a harmonic series, so it sings a note.

The hand decides the rest: where it lands (the middle sounds the low modes,
the edge the high ones), how long it stays on the head, and whether it stays
there to damp it.

## Drum

- **model** - **tabla** (the small, tuned drum), **bayan** (its big bass
  partner), **djembe**, **cajon**, **frame drum**, **talking** drum, **conga**,
  **bongo**, **darbuka**, **riq**, **tar**, **bendir**, **kanjira**, **bata**,
  **mridangam**, **dholak**, **ashiko**, **udu** (a clay pot,
  its air the note) or **cuica** (rubbed inside while the note is held).
- **tune** - in cents.
- **stroke** - **open** (the fingers flat near the edge, lifted at once),
  **slap** (the fingertips cracking, the hand left on the middle), **muted**
  (pressed down and held), **bass** (the palm in the middle), **rim** (the
  fingers on the edge), or **velocity**, which picks muted for soft notes,
  open for the middle and slap for the hardest.
- **position** - moves the stroke towards the middle or the edge.
- **hand** - soft fingertips and palms, to hard, quick fingers.
- **decay** - how long the head rings.
- **damp** - the other hand resting on the head.

## Body

- **pitch drop** - how sharp a hard stroke sends the head before it settles.
- **squeeze** - how many semitones pressure, or the mod wheel, bends the
  head up: the wrist pressing a bayan, the arm squeezing a talking drum.
- **rattle** - a cajón's snares, a djembe's rattles or a frame drum's
  jingles.
- **body** - a djembe's or a cajón's air, booming under a bass stroke.

## Hands

- **rolls** - held notes struck again and again, this many times a second.
- **flam** - each hit is two: a softer grace stroke, then the stroke this
  many milliseconds after it. At 0 a hit is one stroke.
- **spread** - low drums to the left, high to the right.
- **voices**, **velocity**, **octave**, **bend** and **volume**.

With MPE, sliding a finger up lays it on the head and damps it as it rings.

## Cycles

Pick a **cycle** on the hands page and a held note plays a pattern in time
with the song and its swing. Each pattern is a bar long and goes round for a
few bars, with a fill on the last one. While the song is stopped, the first
note you hold starts the bar; a count-in plays the bar before the song's
first, and stopping with a key down carries on in time. In bars of other
lengths the pattern keeps its start and its end, and an extra beat is
played softly.

- **cycle** - **off**, **tumbao** (heel and toe, a slap on two, open tones
  on four), **kuku** (a djembe part, the slaps in pairs), **baladi** and
  **maqsum** (darbuka rhythms of bass and rim strokes), **theka** (eight
  tabla strokes with a pressed bass stroke), **6/8** (twelve to the bar in
  threes), **groove** (bass on one and three, slaps on two and four) or
  **talking** (open and pressed strokes, the head stopped between).
- **bars** - how many bars make a cycle, the last one a fill. At 1 there's
  no fill.
- **feel** - how much each bar changes: soft strokes added or left out,
  tones and slaps swapped, the loudness and timing moving a little. At 0
  every bar plays as written. The changes follow the bar number, so a song
  plays the same every time.
- **press** - how many semitones the second hand presses the head up on a
  pressed stroke.

Hold two notes and they share the pattern: the bass strokes and low tones go
to the lower drum, the rest to the higher. With one note the low tones are
played a fourth under it. In a kit, hold two pads, like the tabla and bayan
in **Tabla Theka**.

Rolls and flams are off while a cycle plays. Let go and it stops, and the
last stroke rings out.

## Kit

Turn on **kit** and the keys from C2 up are sixteen pads, each its own drum, so one track can play a whole hand-drum kit. The sequencer shows the drum grid, one lane per pad, named after its drum and stroke.

- Tap a pad on the drum grid, then set it in the pad card: **drum**, **stroke**, **pitch** and **level**.
- The other knobs (position, hand, decay and so on) work on every pad.
- **Hand Drum Kit** starts with djembe, cajón, tabla, bayan, frame drum and talking drum, **African Kit** with djembes, ashiko, talking drum and batá, **Latin Kit** with congas, bongos, cajón and batá, **Arabic Kit** with darbuka, riq, tar, bendir and frame drum, and **Tabla Kit** with tabla, bayan, mridangam, dholak and kanjira.
- Turn kit off to play one drum across the keys again.

## Macros

The **play** page has eight macro knobs. A patch can name them (Slap,
Ring, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves every drum, 24 semitones at 1. The model, tune,
stroke, the stepped choices and a kit's pads can't be reached; in a kit the
routes move the knobs every pad shares. A change to the position, hand,
decay, damp, pitch drop, body, spread, velocity, flam, feel or press is heard from the
next hit, or the next stroke of a roll. Squeeze, rattle, rolls and volume
move at once. Tap a macro's name to rename it.

## Tips

- The **By Touch** presets play every stroke from one key: play softly for
  muted, harder for open, hardest for a slap.
- **Bayan Swoop** and **Talking Squeeze** are for pressing: play a note, then
  lean on the key or push the mod wheel.
- The **cycles** presets play a pattern from one held note. **Conga Pair**
  and **Tabla Theka** want two.
