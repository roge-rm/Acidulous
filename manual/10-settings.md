# Settings
> What follows you rather than the song.

Nothing in this window is saved into a song. These are statements about you, your
eyes and this particular phone, so opening somebody else's song never changes
how your app behaves.

## display

- **theme** - dark, light, or whatever the phone is doing.
- **interface size** - makes everything larger, for a dense screen or for eyes
  that want it. Four steps; the app lays itself out again as you tap.
- **screen while playing** - whether the screen is allowed to sleep.

## audio

- **audio buffer** - tight, balanced or safe. Tight is the lowest latency the
  device will give and will crackle on a phone that cannot keep up; safe buys a
  phone that cannot the room to finish late. The line under it is live: frames,
  milliseconds and how many times it has dropped out.
- **worst block** - the longest a single block of audio took, against the time
  it had, and where that time went. This is the number a dropout is about: the
  load meter beside the transport is an average and cannot show a spike, which
  is why a phone can read a comfortable load and still click. **reset** clears
  it, so what it shows is since you last looked.

  Only blocks that ran *uninterrupted* are counted. A phone runs far more
  threads than it has cores, so now and then the system takes the audio thread
  off its core and gives it back a moment later - and a block that this happens
  to looks enormous while having done no more work than any other. Those are
  thrown away rather than believed, and **% interrupted** is how many. It is
  also the most useful number here: if it is low, a high worst block means the
  song is asking for too much and freezing or **lean** will help. If it is
  high, the song is not the problem - the device is busy, and closing other
  apps will do more than anything in this window.
- **worst track** - what each track costs, dearest first, so the list says
  what to freeze. A **❄** means that track was playing frozen audio when it
  cost that much, which should be next to nothing - if you see one, the freeze
  is not doing its job.

  This one is each track's **worst block in a hundred**, not its worst block.
  A single peak over a whole song is set by one unlucky moment and nothing
  afterwards can bring it down, which made it swing by a quarter between two
  runs of the same song - useless for telling whether a change helped. A
  figure that needs one block in a hundred to agree does not move like that.
  Give it a few seconds of playing before believing it, and expect a track
  that only plays in one scene to be less settled than one that plays
  throughout.
- **machine voice limit** - how many notes a track may hold at once. The oldest
  goes first.
- **quality** - what to give up when a device cannot keep up. At **lean**:
  - the **amp** and the **distortion** run at the plain rate instead of
    oversampling, so they alias where they used to be clean. On the amp this
    is about half its cost, and it is the dearest effect here;
  - the **reverb** is built from half as many combs and allpasses - a thinner
    tail, for about half the processor;
  - **struck objects** keep half their partials, which affects the patches
    that ask for more than twelve and leaves the rest alone;
  - the **three-oscillator synth** halves a unison stack, never below two, so
    a wide pad narrows rather than turning into one voice. A patch stacked
    eight deep costs about a third less; a patch that was never stacked is
    left alone. It also lets no more than **six released notes** ring on at
    once: past that the oldest tail fades out over ten milliseconds. A patch
    with a long release pays for its release rather than its notes - a bell
    arp can have a dozen tails going behind the one you are playing - and
    this is about forty per cent of what such a patch costs. Held notes are
    never touched;
  - the **granular machine** has half as many grains to share out. A dense
    cloud thins - about forty per cent cheaper - and a sparse one, which
    never used half the pool, is left as it was.

  Nothing else changes, and nothing changes about what you have recorded. A
  note you are holding keeps whatever it was born with, so the switch - and
  **auto** moving on its own - never alters a note under your fingers. Only a
  tail you have already let go of can be shortened.

  Most of what a phone struggles with is not in this list and never was: a
  machine doing work at audio rate that nothing at audio rate asked for. That
  is a fault rather than a setting, and it is fixed where it is found.

  **Exports and frozen tracks ignore this setting.** Writing a file has no
  deadline to miss, so it is always done at full quality however the setting
  is left.

## record

The depth recordings and exports are written at.

## songs

What a new song starts as: tempo, signature, the machine on its first track, and
whether its first track comes with a scale already set.
