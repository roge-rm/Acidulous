# Cipher

> A vocoder whose band map is the instrument.

Cipher is a vocoder: it analyses one sound in bands and imposes that shape on another. What makes this one different is that **the map between the analysis bands and the synthesis bands is a thing you can change**, and that map is where all the interesting sounds are.

## The two sides

The **modulator** is what is analysed - usually a voice, from the live input. The **carrier** is what gets shaped, and Cipher has its own built in: an oscillator with **detune**, **pw**, **sub**, **noise** and **cardrive**, so you do not need a second track to play one.

## The bank

- **bands** - how many. Few is robotic and intelligible; many is smooth and less so.
- **low**, **high** and **slope** - where the bank sits and how it is spread across that range.
- **q** - how narrow each band is.

## The map, which is the extra

Normally band 1 drives band 1. Here you can **remap** it: reverse the order so a bright sound comes out dark, spread it so the voice covers a wider range than it occupied, **freeze** it so the current shape is held while the carrier keeps playing, or **smear** it so neighbouring bands bleed into each other.

**role** and the **dry**/**wet** pair decide which side is which and how much of each is heard, and **unvoiced** decides what happens to consonants - which a vocoder with no answer for turns into a lisp.

## Following

**attack**, **release** and **smear** are the envelope followers on each band. Fast is articulate and can chatter; slow is smooth and can slur.

## Using it well

**Consonants are the whole problem.** Turn `unvoiced` up until the words are intelligible, then back off until it stops hissing.

**Freeze on a vowel is a pad.** Hold the map and play a chord underneath, and the vowel becomes an instrument.

**Fewer bands than you think.** Sixteen articulate bands beat forty smooth ones for anything with words in it.
