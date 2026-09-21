# Mosaic

> The multisample player: zones across the keyboard and across velocity, and a grain engine over the top.

Mosaic plays maps of samples. Where Forage gives you thirteen pads, Mosaic gives you an instrument: a file assigned to a range of keys and a range of velocities, and as many of those as the instrument needs.

## The map

A **zone** is a file, a key range, a velocity range, a root note and a tuning. Build one by hand from your own recordings, or load a **SoundFont** and get the whole map at once - the reader is the app's own, so nothing is converted outside and nothing is lost on the way in.

- **keyfade** and **velfade** - crossfades between neighbouring zones, so the seams stop being audible. Without them a multisample is a series of steps; with them it is an instrument.
- **scan** and **scanamt** - a **layer scan**: move continuously through the layers that would otherwise be chosen by velocity. It is how you play the quiet sample and the loud one as a blend rather than as a choice.

## Playing a zone

**start**, **loop**, **reverse**, **envfrom** and **filemod** decide how the file itself is read, then there is a full synth after it: a filter with its own envelope and key tracking, two envelopes, two LFOs, glide and voice modes.

## The grains

**grain** turns on a granular layer over the map - **gsize**, **gdensity**, **gpos**, **gspray**, **gpitch** and **grate**. The same file, but read as a cloud rather than as a sample, which is a different instrument on the same material and does not need a second machine.

## Using it well

**Set the crossfades before you judge the map.** Most maps that sound wrong are maps with hard seams.

**A root note that is wrong makes everything wrong.** It is the one field with no forgiving setting.

**Scan is the control people do not find.** A two-layer map with the scan on a mod wheel is a dynamic instrument from two files.

**Big maps are big.** Mosaic can hold a lot; the sound library's delete page will tell you what a song is using before you remove anything.
