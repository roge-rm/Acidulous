# The machines
> Nineteen instruments, a four-track for your recordings, and what each is for.

Every machine here was written for this app. None is sampled from anything else,
and none is a copy of anything: they stand in familiar places and are their own
designs. Tap a rack's machine name to change it.

## Bass and lead

- **Subvert** - the signature bass mono. Accent is velocity and slide is
  legato, so a line is played rather than programmed: hold one note into the
  next to slide, hit it hard to accent.
- **Trinity** - three oscillators with wavetables, density and drift, two
  filters, six envelopes, three LFOs and a twelve-slot modulation matrix. The
  general-purpose poly.
- **Ratio** - six-operator FM. Two algorithms with a morph between them, so the
  routing itself is something you can sweep, and ratios you can snap to whole
  numbers or skew off them.

## Keys and air

- **Manual** - an organ. Two manuals and pedals over one shared generator, four
  models and a rotary cabinet. The keys tap a shared set of wheels, which is why
  its polyphony costs nothing and why it sounds like an organ.
- **Cumulus** - pads built as a spectrum rather than as oscillators: bands of
  partials, four frames morphed between, stretch, vowels and drift.
- **Mosaic** - the multisample player. Key and velocity zones, crossfades, a
  layer scan, and a grain engine over the whole map.

## Modelled instruments

These are solved rather than sampled, so they respond to how you play them.

- **Filament** - strings. Six ways to excite them, including the audio input,
  plus a sympathetic bank, stiffness, tension and preparation.
- **Brazen** - brass, tuba to trumpet. Lips blown open against a tube, and a
  section whose players listen to each other.
- **Timber** - woodwinds. Reed, double reed and air jet, cylinder and cone, and
  a tube that goes on below the note you fingered.
- **Resonance** - modal percussion. Eight objects struck somewhere with
  something, ringing into each other.

## Drums

- **Hexbeat** - a drum synthesizer in the small-box vocabulary, grown to the kit
  those boxes never had. Thirteen voices, nothing sampled.
- **Genesis** - the big drum box: a swept kick, six-square metal, circuit drift,
  and a bus compressor with the kick wired to its side chain.
- **Forage** - the sample drum machine. Thirteen pads, your own files, with a
  filter, a crusher and a pitch envelope on each.

## Sound as material

- **Pollen** - granular, over a file you mount or the sound coming in live.
- **Dice** - a slicer. A loop cut into pieces, with a probability on every
  trigger, and a hold that makes a roll repeatable.
- **Cipher** - a vocoder, where the map between the analysis bands and the
  synthesis bands is the instrument: remap it, freeze it, smear it, swap it.
- **Molt** - a voice you write for. Sing a take in and the piano roll tunes it:
  draw the line, sing anything, and the take is pulled onto the notes.

## Anything else

- **Formulate** - an 8-bit machine with tracker tables and a small expression
  language, so a waveform is something you write rather than something you pick.
- **Nexus** - a modular whose blocks are this app's own instruments and whose
  patch is text you can read.

## Audio

- **Bias** - a four-track that runs along the song. Where every other machine
  plays the notes in a cell, Bias plays the *recording* in a cell: four lanes,
  and they sound together, so choosing between three takes is muting two of
  them and doubling a vocal is unmuting a second. **audio…** on a lane puts a
  recording there, and it belongs to that cell - a take that crosses four
  scenes is four cells naming the same file, so no second timeline exists and
  scenes of different lengths need nothing said about them.

  The level and the mute on each lane are ordinary parameters, which is the
  whole reason they are there rather than on the recording: they automate in
  the strip under the grid, they map to a pad or a knob, and they record while
  you play. A muted section is a mute drawn in that cell.

  Recording onto it is in [Recording and samples](08-recording-and-samples.md):
  arm a lane, press record, press play, and what you sing is cut at the scene
  lines when you stop.

  Opening a Bias cell gives you the lanes themselves, along this cell's whole
  cycle - both passes of a scene set to repeat, because that is what a
  four-track plays. Drag a lane's body to move where it comes in; drag either
  end to trim the recording without moving it; tap the number at the left to
  mute the lane. An empty lane is a tap away from the library.

  Audio does not stretch. A take enters on the bar wherever it is put, and runs
  at the speed it was recorded at; when that is not the tempo the scene plays
  at, the lane says so in amber.

## Patches

Every machine ships with patches, shelved by family. **patch** at the top of the
panel opens the browser; your own saved patches get a tab of their own and are
the only ones you can delete.
