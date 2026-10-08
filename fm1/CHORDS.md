# Lyric chords for Diction on the FM-1

A way to give Diction words on the M-VAVE FM-1 without a computer: one chord
on the keyboard is one sung syllable. It's a design, not built yet; the
table has been checked against English (below) but not yet under fingers.

## The idea

Court stenographers write a syllable at a time on a keyboard of about 23
keys in banks: starting consonants under the left hand, the vowel under the
thumbs, ending consonants under the right. The FM-1 has 27 keys, F3 to G5,
and its black keys fall in groups that make the same banks easy to find
without looking:

```
 black   F#3 G#3 A#3   C#4 D#4   F#4 G#4 A#4   C#5 D#5   F#5
         T-  P-  H-     O   E     -F  -P  -L    -T  -D   delete
 white  F3 G3  A3  B3  C4 D4 E4  F4 G4  A4  B4  C5 D5  E5   F5   G5
        S- K-  W-  R-  A  @  U   *  -R  -B  -G  -S -Z  hold rest play
        |-- start --| |vowel-|  |----------- end ---------| |-commands-|
```

- **Start** (F3 to B3, 7 keys): steno's left bank, S T K P W H R.
- **Vowel** (C4 to E4, 5 keys): steno's A O E U, and a schwa key, @, in the
  middle on D4.
- **End** (F4 to D#5, 11 keys): steno's right bank, F R P B L G and S T Z D,
  and * on F4.
- **Commands** (E5 to G5): hold the last vowel over this note, a note with no
  words, delete the last syllable, and sing the line back.

The FM-1 reads chords like a steno machine: a chord is every key pressed
until the last one is let go, so the keys can be pressed together with two
hands or rolled in one after another with one, as long as one stays down.
Letting go sings the syllable straight away, and puts it on the next note.
The keyboard's matrix has diodes and no ghosting (SLOOP's `fm1_input.h`), so
every key of a chord reads.

It writes sounds, not spelling: *night* and *knight* are the same chord, and
so is any word Diction's dictionary doesn't have, made up or not. That's also
all Diction needs: phone codes, through `Machine::lyric()`.

## The rules

**Starting consonants** are steno's, where steno is phonetic. Seven are one
key (S T K P W H R, H being HH); the rest are combinations to learn:

| Sound | Steno | | Sound | Steno | | Sound | Steno |
|---|---|---|---|---|---|---|---|
| B | PW | | L | HR | | CH | KH |
| D | TK | | M | PH | | SH | SH |
| F | TP | | N | TPH | | TH | TH |
| G | TKPW | | V | SR | | DH | THR |
| JH | SKWR | | Y | KWR | | ZH | SKWHR |
| Z | STKPW | | | | | | |

A cluster is its sounds' keys together: STR is S + T + R, PL is P + HR, GR is
TKPW + R. Three would read as something commoner and have their own:
SH R is SWHR (SHR is SL), TH R is TWHR (THR is DH), G W is TKPWH.

**Vowels** are steno's long-vowel combinations:

| Sound | As in | Steno | | Sound | As in | Steno |
|---|---|---|---|---|---|---|
| AE | cat | A | | EY | day | AEU |
| AA | hot | O | | IY | see | AOE |
| EH | bed | E | | AY | my | AOEU |
| AH | but | U | | OW | go | OE |
| IH | sit | EU | | UW | blue | AOU |
| UH | good | AO | | AW | now | OU |
| AO | saw | AU | | OY | boy | OEU |
| AX | **a**bout | @ | | ER | her | @ with -R |

**A Y after a consonant** (cute, few, music, popular) goes with the vowel, as
the @ key added to it: @AOU is Y UW, @AO is Y UH, @U is Y AH, @U with -R is
Y ER. On its own the same chord needs no consonant, so *you* is just @AOU
under the thumbs. A Y at the start of a word is KWR (yes, yet).

**Ending consonants** are steno's too, with * on F4 for the voiced ones:

| Sound | Steno | | Sound | Steno | | Sound | Steno |
|---|---|---|---|---|---|---|---|
| M | PL | | SH | RB | | V | *F |
| N | PB | | CH | FP | | TH | *T |
| K | BG | | JH | PBLG | | DH | *D |
| NG | PBG | | | | | ZH | *Z |

A cluster is its sounds' keys together (ND is PB + D, LT is L + T). A chord
can't show the order of its keys, so two rules settle it the way the spelling
does:

- **A last S after P, T, K, F or TH is on -Z**, as steno writes plurals:
  *gets* is TZ, *six* BGZ, *facts* BGTZ. So -S -T is always ST (*just*,
  *next*) and -S -BG always SK (*ask*).
- **A Z before D is on -S**, as it's spelled: *used*, *closed*, *realised*
  are SD. So -Z -D is always DZ (*kids*).

And a few clusters would read as something else, so have their own:

| Sound | Steno | Because | As in |
|---|---|---|---|
| NG K, N K | *PBG | PBG + BG is NG | think, bank |
| M P | *PL | PL + P is M | jump |
| L P | *LG | L + P is M | help |
| L M | *PLB | L + PL is M | film |
| R B | *RB | R + B is SH | curb |
| L B | *LB | | bulb |
| N JH | *PBLG | PB + PBLG is JH | change |
| S K T | BGSD | BGS + T is K S T | asked |

## Examples

| Words | Syllables | Chords (steno) | Keys |
|---|---|---|---|
| happy | HH AE · P IY | HA · PAOE | A#3 C4 · G#3 C4 C#4 D#4 |
| birthday | B ER TH · D EY | PW@*RT · TKAEU | G#3 A3 D4 F4 G4 C#5 · F#3 G3 C4 D#4 E4 |
| to you | T UW · Y UW | TAOU · @AOU | F#3 C4 C#4 E4 · C4 C#4 D4 E4 |
| hello | HH AX · L OW | H@ · HROE | A#3 D4 · A#3 B3 C#4 D#4 |
| world | W ER L D | W@RLD | A3 D4 G4 A#4 D#5 |
| twinkle | T W IH NG · K AX L | TWEUPBG · K@L | F#3 A3 D#4 E4 G#4 A4 B4 · G3 D4 A#4 |
| star | S T AA R | STOR | F3 F#3 C#4 G4 |
| thinks | TH IH NG K S | THEU*PBGZ | F#3 A#3 D#4 E4 F4 G#4 A4 B4 D5 |

Most syllables are three to six keys across the three banks; by use, 75%
of them need five keys or fewer and 96% seven or fewer.

## How well it covers English

`fm1/chords.py` checks the table. It splits every word of Diction's
dictionary into syllables as `Lyrics.syllables` does, writes each as a chord,
reads the chord back the way the FM-1 would (each bank on its own, a piece of
chord as the commonest thing written with it) and counts, weighted by how
often the words are used in a list of 50,000 English words by use (subtitles,
from [FrequencyWords](https://github.com/hermitdave/FrequencyWords)):

```
python3 fm1/chords.py en_50k.txt            the check
python3 fm1/chords.py en_50k.txt -v         more of what it gets wrong
python3 fm1/chords.py en_50k.txt --table    every chord, as the tables below
```

```
syllables: 9344 kinds; chords in the tables: start 73, vowel 20, end 166
sung right: 99.98% of syllables sung (misread 0.02%, can't be written 0.00%)
```

What's still misread is rare: *strength* and *length* (NG K TH is read NG K
T), *harsh* and *marsh* (R SH is read SH), *backyard*. The frequency list is
only for the check; nothing of it goes on the FM-1.

The three tables the FM-1 needs are 259 entries, a couple of kilobytes of
flash. Unlike the dictionary (1.5 MB, too big for the FM-1's 1 MB flash), no
word list is needed at all.

## Still to work out

- **Under fingers.** Whether the vowel bank, two black and three white keys
  in the middle, is comfortable for both thumbs on the FM-1's keys, and
  whether rolling a chord in one hand is quick enough. A MIDI keyboard on a
  computer can try it before the box arrives: the same 27 notes, sung
  through Diction on the desktop.
- **Learning it.** The screen can show the chord for each sound while the
  layer is held, and a spelling mode (a letter a key, with prediction from a
  small word list) can show the chord for a word it finds, to learn from.
- **Where the words go.** Each syllable to the next note of the selected
  track's pattern, with the commands for holding and skipping notes, as
  Diction's lyrics work in Acidulous: a syllable a note, "-" holding.
- **Live.** With the melody coming in on MIDI, the whole keyboard is free to
  chord syllables as they're sung.

## The tables

Every chord in use at least 0.02% of the time, commonest first. Steno
spellings follow this layout's key order, which puts -S before -T and -Z
before -D (steno has T S D Z). `--table` prints them all.

### Starting consonants (left hand, F3 to B3)

| Sounds | Keys | Steno | Use | As in |
|---|---|---|---|---|
| W | A3 | W | 6.20% | what, we, was |
| DH | F#3 A#3 B3 | THR | 6.13% | the, that, this |
| T | F#3 | T | 5.92% | to, time, take |
| M | G#3 A#3 | PH | 4.79% | me, my, man |
| Y | G3 A3 B3 | KWR | 4.78% | you, your, yeah |
| HH | A#3 | H | 4.76% | he, have, here |
| N | F#3 G#3 A#3 | TPH | 4.48% | no, not, know |
| S | F3 | S | 4.07% | so, see, some |
| L | A#3 B3 | HR | 4.05% | like, let, look |
| D | F#3 G3 | TK | 3.96% | do, don, did |
| B | G#3 A3 | PW | 3.92% | be, but, about |
| K | G3 | K | 3.80% | can, come, okay |
| R | B3 | R | 2.67% | right, really, very |
| G | F#3 G3 G#3 A3 | TKPW | 2.61% | get, go, got |
| F | F#3 G#3 | TP | 2.58% | for, first, find |
| P | G#3 | P | 1.89% | people, put, happened |
| V | F3 B3 | SR | 1.40% | very, never, over |
| SH | F3 A#3 | SH | 1.38% | she, should, sure |
| JH | F3 G3 A3 B3 | SKWR | 1.10% | just, job, john |
| S T | F3 F#3 | ST | 1.08% | still, stop, mr. |
| TH | F#3 A#3 | TH | 1.04% | think, something, thank |
| CH | G3 A#3 | KH | 0.61% | actually, change, check |
| Z | F3 F#3 G3 G#3 A3 | STKPW | 0.50% | music, exactly, crazy |
| P R | G#3 B3 | PR | 0.47% | pretty, problem, probably |
| T R | F#3 B3 | TR | 0.46% | try, trying, true |
| F R | F#3 G#3 B3 | TPR | 0.43% | from, friend, friends |
| P L | G#3 A#3 B3 | PHR | 0.37% | please, place, play |
| G R | F#3 G3 G#3 A3 B3 | TKPWR | 0.31% | great, group, ground |
| S P | F3 G#3 | SP | 0.29% | speak, special, hospital |
| B R | G#3 A3 B3 | PWR | 0.27% | brother, bring, break |
| D R | F#3 G3 B3 | TKR | 0.25% | drink, children, dr. |
| K R | G3 B3 | KR | 0.24% | crazy, secret, christmas |
| K L | G3 A#3 B3 | KHR | 0.23% | close, clear, clean |
| B L | G#3 A3 A#3 B3 | PWHR | 0.21% | problem, probably, blood |
| S K | F3 G3 | SK | 0.21% | school, excuse, scared |
| S T R | F3 F#3 B3 | STR | 0.16% | street, straight, strong |
| K W | G3 A3 | KW | 0.14% | quite, question, quiet |
| TH R | F#3 A3 A#3 B3 | TWHR | 0.13% | through, three, throw |
| S L | F3 A#3 B3 | SHR | 0.12% | sleep, seriously, obviously |
| F L | F#3 G#3 A#3 B3 | TPHR | 0.09% | floor, fly, flight |
| S M | F3 G#3 A#3 | SPH | 0.09% | small, christmas, smart |
| ZH | F3 G3 A3 A#3 B3 | SKWHR | 0.07% | pleasure, decision, usually |
| S W | F3 A3 | SW | 0.07% | sweet, swear, sweetheart |
| G L | F#3 G3 G#3 A3 A#3 B3 | TKPWHR | 0.06% | glad, english, glass |
| S K R | F3 G3 B3 | SKR | 0.04% | screaming, screams, screw |
| T W | F#3 A3 | TW | 0.04% | between, twice, twenty |
| S P L | F3 G#3 A#3 B3 | SPHR | 0.03% | explain, split, explosion |
| S N | F3 F#3 G#3 A#3 | STPH | 0.03% | snow, snake, snap |
| S P R | F3 G#3 B3 | SPR | 0.02% | spread, spring, desperate |

### Vowels (thumbs, C4 to E4)

| Sounds | Keys | Steno | Use | As in |
|---|---|---|---|---|
| IH | D#4 E4 | EU | 14.29% | it, is, in |
| AH | D4 | @ | 13.36% | the, a, and |
| IY | C4 C#4 D#4 | AOE | 9.63% | we, me, he |
| UW | C4 C#4 E4 | AOU | 7.56% | you, to, do |
| AY | C4 C#4 D#4 E4 | AOEU | 7.28% | i, my, like |
| EH | D#4 | E | 7.10% | there, get, well |
| AH | E4 | U | 6.76% | of, what, but |
| AE | C4 | A | 6.70% | that, have, can |
| AA | C#4 | O | 5.88% | on, was, not |
| ER | D4 G4 | @R | 4.99% | her, were, our |
| OW | C#4 D#4 | OE | 4.54% | no, know, so |
| EY | C4 D#4 E4 | AEU | 4.13% | they, okay, take |
| AO | C4 E4 | AU | 4.05% | for, your, all |
| AW | C#4 E4 | OU | 1.92% | out, about, now |
| UH | C4 C#4 | AO | 1.26% | good, look, would |
| Y UW | C4 C#4 D4 E4 | AO@U | 0.24% | few, excuse, music |
| OY | C#4 D#4 E4 | OEU | 0.22% | boy, point, boys |
| Y AH | D4 E4 | @U | 0.04% | ridiculous, particular, popular |
| Y UH | C4 C#4 D4 | AO@ | 0.02% | security, pure, curious |

### Ending consonants (right hand, F4 to D#5)

| Sounds | Keys | Steno | Use | As in |
|---|---|---|---|---|
| T | C#5 | T | 8.19% | it, that, what |
| N | G#4 A4 | PB | 7.84% | in, on, don |
| R | G4 | R | 4.10% | for, your, are |
| Z | D5 | Z | 3.40% | is, was, as |
| L | A#4 | L | 3.34% | all, well, will |
| NG | G#4 A4 B4 | PBG | 2.76% | going, something, doing |
| D | D#5 | D | 2.67% | did, good, would |
| M | G#4 A#4 | PL | 2.60% | him, come, from |
| V | F4 F#4 | *F | 2.31% | of, have, love |
| K | A4 B4 | BG | 2.25% | like, back, look |
| S | C5 | S | 2.17% | this, yes, us |
| N D | G#4 A4 D#5 | PBD | 2.01% | and, find, around |
| S T | C5 C#5 | ST | 1.05% | just, first, must |
| F | F#4 | F | 0.77% | if, off, life |
| P | G#4 | P | 0.77% | up, stop, keep |
| N T | G#4 A4 C#5 | PBT | 0.71% | want, went, different |
| DH | F4 D#5 | *D | 0.44% | with, breathe, smooth |
| NG K | F4 G#4 A4 B4 | *PBG | 0.38% | think, thank, drink |
| L D | A#4 D#5 | LD | 0.37% | told, old, world |
| CH | F#4 G#4 | FP | 0.34% | much, which, such |
| N Z | G#4 A4 D5 | PBZ | 0.27% | means, happens, questions |
| K S | A4 B4 D5 | BGZ | 0.26% | looks, makes, six |
| G | B4 | G | 0.25% | big, exactly, dog |
| N S | G#4 A4 C5 | PBS | 0.24% | once, since, chance |
| K T | A4 B4 C#5 | BGT | 0.23% | exactly, fact, perfect |
| T S | C#5 D5 | TZ | 0.22% | its, minutes, gets |
| L Z | A#4 D5 | LZ | 0.20% | girls, chuckles, miles |
| R T | G4 C#5 | RT | 0.19% | start, heart, part |
| TH | F4 C#5 | *T | 0.18% | both, death, truth |
| R D | G4 D#5 | RD | 0.15% | hard, lord, scared |
| B | A4 | B | 0.15% | job, absolutely, club |
| M Z | G#4 A#4 D5 | PLZ | 0.15% | times, comes, seems |
| R Z | G4 D5 | RZ | 0.14% | years, yours, cheers |
| SH | G4 A4 | RB | 0.14% | wish, finish, fish |
| JH | G#4 A4 A#4 B4 | PBLG | 0.13% | age, message, marriage |
| D Z | D5 D#5 | ZD | 0.11% | kids, needs, words |
| N D Z | G#4 A4 D5 D#5 | PBZD | 0.11% | friends, hands, sounds |
| N T S | G#4 A4 C#5 D5 | PBTZ | 0.11% | wants, parents, grunts |
| NG Z | G#4 A4 B4 D5 | PBGZ | 0.10% | things, rings, feelings |
| L F | F#4 A#4 | FL | 0.10% | yourself, myself, himself |
| Z D | C5 D#5 | SD | 0.09% | used, supposed, surprised |
| L P | F4 A#4 B4 | *LG | 0.08% | help, helpful, sculpture |
| P T | G#4 C#5 | PT | 0.07% | kept, except, stopped |
| F T | F#4 C#5 | FT | 0.07% | left, gift, lift |
| P S | G#4 D5 | PZ | 0.07% | perhaps, cops, keeps |
| R S | G4 C5 | RS | 0.07% | course, force, horse |
| NG K S | F4 G#4 A4 B4 D5 | *PBGZ | 0.07% | thanks, thinks, drinks |
| V D | F4 F#4 D#5 | *FD | 0.06% | loved, saved, moved |
| V Z | F4 F#4 D5 | *FZ | 0.06% | lives, loves, gives |
| S K | A4 B4 C5 | BGS | 0.05% | ask, risk, desk |
| L T | A#4 C#5 | LT | 0.05% | felt, fault, difficult |
| N JH | F4 G#4 A4 A#4 B4 | *PBLG | 0.05% | change, strange, challenge |
| K S T | A4 B4 C5 C#5 | BGST | 0.05% | next, fixed, text |
| R K | G4 A4 B4 | RBG | 0.04% | dark, york, mark |
| L S | A#4 C5 | LS | 0.04% | else, false, pulse |
| R M | G4 G#4 A#4 | RPL | 0.04% | arm, form, warm |
| M P | F4 G#4 A#4 | *PL | 0.04% | jump, empty, camp |
| N CH | F#4 G#4 A4 | FPB | 0.03% | lunch, french, bunch |
| G Z | B4 D5 | GZ | 0.03% | drugs, legs, dogs |
| S T S | C5 C#5 D5 | STZ | 0.03% | guests, tests, ghosts |
| N S T | G#4 A4 C5 C#5 | PBST | 0.03% | against, convinced, experienced |
| M D | G#4 A#4 D#5 | PLD | 0.03% | named, seemed, ashamed |
| R N | G4 G#4 A4 | RPB | 0.03% | born, horn, warn |
| SH T | G4 A4 C#5 | RBT | 0.02% | finished, pushed, punished |
| R JH | G4 G#4 A4 A#4 B4 | RPBLG | 0.02% | george, charge, large |
| F S | F#4 D5 | FZ | 0.02% | laughs, scoffs, coughs |
| S K T | A4 B4 C5 D#5 | BGSD | 0.02% | asked, risked, masked |
| R T S | G4 C#5 D5 | RTZ | 0.02% | starts, parts, hearts |
| R D Z | G4 D5 D#5 | RZD | 0.02% | towards, records, cards |
| K T S | A4 B4 C#5 D5 | BGTZ | 0.02% | facts, effects, acts |
