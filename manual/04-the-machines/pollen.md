# Pollen

> Granular clouds from a file or the live input, whose grains can spawn more grains.

Pollen plays a sound as a cloud of short grains. The source is a file you load
or the live input, recorded into a loop as you play.

## The cloud

- **size** and **sizespread** - how long each grain is, and how much that
  varies.
- **density** and **jitter** - how many grains per second, and how irregular.
- **window** and **skew** - the shape of each grain's fade in and out.
- **position**, **scan** and **spray** - where grains come from in the buffer,
  whether that point moves and how far they scatter around it.
- **snap** - lines grains up with the hits in the source, so rhythmic material
  stays rhythmic.
- **panspread** and **width**.

## Live input

**source** switches to the input. **buffer** sets how much is kept, **freeze**
holds it and **capture** grabs what's in it. A live buffer isn't saved with the
song and is silent in an export, and the panel reminds you of that.

## Pollination

**bloom** and **generations** let grains spawn more grains at a related
position and pitch, up to the depth you set. A little thickens the sound, and a
lot turns one note into a changing texture. **drift** and **mutate** set how far
the new grains wander.

## Tips

- Density and size work against each other. Long grains at high density make a
  wall, and short ones make a texture.
- Use **snap** on anything with a beat.
- The pitch scatter can be locked to a scale so the cloud stays in key.
- On lean quality (Settings) the cloud uses half as many grains.
