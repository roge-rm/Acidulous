# Pollen

> Granular clouds that seed their own, from a file or from what is coming in live.

Pollen reads a buffer as a cloud of short grains rather than as a sample. It has two sources - a file you mount, or **the live input**, captured into a ring as you play - and one view over both.

## The cloud

- **size** and **sizespread** - how long a grain is, and how much they vary.
- **density** and **jitter** - how many per second, and how irregular.
- **window** and **skew** - the grain's envelope shape.
- **position**, **scan** and **spray** - where in the buffer grains are taken from, whether that point moves, and how far either side they scatter.
- **snap** - pulls grain starts onto the buffer's own transients, so a rhythmic source stays rhythmic instead of becoming a wash.
- **panspread** and **width**.

## The live source

**source** switches to the input, **buffer** sets how much is kept, **freeze** decides whether the ring rolls or holds, and **capture** grabs what is in it. A live cloud is **not saved with the song and is silent on export**, which the panel says on screen rather than leaving you to find out.

## The extra: pollination

**bloom** and **generations**. Grains seed further grains: each one can spawn another, at a related position and pitch, up to a depth you set. At low settings it thickens; at high settings one note becomes a texture that keeps unfolding. **drift** and **mutate** decide how far the children stray from their parents.

## Using it well

**Density and size trade against each other.** Long grains at high density is a wall; short grains at high density is a texture. Pick one and adjust the other.

**Use `snap` on anything with a beat in it.** It is the difference between granulating a drum loop and erasing one.

**Scatter onto a scale.** The pitch scatter can be quantised to a key, so a cloud stays in the song rather than beside it.
