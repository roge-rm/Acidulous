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
- **machine voice limit** - how many notes a track may hold at once. The oldest
  goes first.
- **quality** - what to give up when a device cannot keep up. At **lean**:
  - the **amp** and the **distortion** run at the plain rate instead of
    oversampling, so they alias where they used to be clean. On the amp this
    is about half its cost, and it is the dearest effect here;
  - the **reverb** is built from half as many combs and allpasses - a thinner
    tail, for about half the processor;
  - **struck objects** keep half their partials, which affects the patches
    that ask for more than twelve and leaves the rest alone.

  Nothing else changes, and nothing changes about what you have recorded.

## record

The depth recordings and exports are written at.

## songs

What a new song starts as: tempo, signature, the machine on its first track, and
whether its first track comes with a scale already set.
