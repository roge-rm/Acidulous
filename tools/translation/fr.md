# Canadian French: how Acidulous is translated

The French is Canadian French, as written in Quebec. It lives in `values-fr`
(the UI) and `manual/fr` (the manual), so every French locale gets it.

## Voice

The English follows Dan's style, and the French should read the same way:

- Say what something does and how to use it. Plain, everyday words.
- Short. As short as the English, or nearly: French runs longer, so cut
  words rather than add them.
- No bold sentences, no em dashes (use a comma, a colon or a new sentence),
  no "X, not Y" turns, no summary or teaser lines.
- "tu" is never used; address the reader as "vous", but prefer the
  imperative and impersonal forms the English already uses ("Touchez
  la piste", "Le bouton change...").
- Knob, button and chip labels are lowercase, as in English.

## Canadian usage

- Prefer the terms the Office québécois de la langue française (OQLF)
  recommends over anglicisms: courriel, clavarder, fin de semaine,
  magasiner are the kind of choice meant. In music, use the French term
  where one is in use ("échantillon", "boîte à rythmes", "piste"); keep a
  loanword where musicians in Quebec say it ("swing", "groove", "riff").
- Typography as in Quebec: no space before ? ! ; but a no-break space
  (U+00A0) before ":" and inside « » (« ainsi »). Use the typographic
  apostrophe ’ (U+2019) everywhere, which also spares the XML escape.
- Numbers keep the format the code gives them; don't edit placeholders.

## Never translated

Machine names (Reflux, Trinity, Draw, Hammer...), effect names (Delay,
Reverb, Amp...), Nexus module names, patch and bank names, scale names,
demo songs, note names (C4, F♯), units (Hz, dB, ms, st, bpm), MIDI terms in
notation (CC 74, ch 1), file formats (WAV, FLAC, MIDI), product and
protocol names (Ableton Link, MPE, TalkBack, USB, Bluetooth).

## Strings files (values-fr)

- Translate only `<string>`, `<item>` and `<plurals>` text. Keep every
  `name=`, every attribute and the order of entries. Comments stay in
  English (they're notes for translators).
- Skip anything with `translatable="false"`.
- Keep placeholders exactly: `%1$s`, `%d`, `%.1f`, `%2$-8s`... You may move
  them within the sentence; never change their number or letter.
- Plurals: French puts 0 and 1 in `one` (« 0 mesure », « 1 mesure »,
  « 2 mesures »). Give `one` and `other`.
- Android escaping still applies: an apostrophe written `'` needs `\'`, so
  use ’ instead. A double quote needs `\"`. Keep `\n` as it is.
- Labels on knobs and chips must stay short: about the English length,
  never more than 4 or 5 characters longer. Abbreviate the way a French
  synth panel would (« rés. » for résonance, « fréq. » for fréquence) only
  when the word won't fit; prefer a shorter real word.

## Glossary

| English | Français (Canada) |
|---|---|
| song | morceau |
| scene | scène |
| clip | clip |
| track | piste |
| group (of tracks) | groupe |
| machine | machine |
| patch | son (pl. sons) |
| bank | banque |
| family (of patches) | famille |
| knob | bouton |
| switch | interrupteur |
| panel | panneau |
| editor | éditeur |
| piano roll | grille de notes |
| step | pas |
| pattern | motif |
| note | note |
| velocity | vélocité |
| tempo / bpm | tempo / bpm |
| swing | swing |
| bar / beat | mesure / temps |
| loop | boucle |
| sample | échantillon |
| take (recording) | prise |
| record / recording | enregistrer / enregistrement |
| play / stop | jouer / arrêter |
| export | exporter |
| import | importer |
| undo / redo | annuler / rétablir |
| settings | réglages |
| manual | manuel |
| help | aide |
| mixer | mixage |
| effect / insert / send | effet / insertion / envoi |
| volume / pan | volume / panoramique (label: pano) |
| mute / solo | muet / solo |
| master (output) | sortie principale (label: général) |
| sidechain | entrée latérale |
| quantise | quantifier |
| groove | groove |
| humanise | humaniser |
| arpeggiator / arp | arpégiateur / arpège |
| chord | accord |
| scale (musical) | gamme |
| key (musical) | tonalité |
| key (keyboard key) | touche |
| keyboard | clavier |
| octave | octave |
| transpose | transposer |
| tune (knob) / tuning (temperament) | accord / accordage |
| detune | désaccord |
| pitch | hauteur |
| pitch bend / bend | pitch-bend / bend (label: bend) |
| mod wheel | molette de modulation |
| pressure / aftertouch | pression / aftertouch |
| slide (MPE) | glissé |
| sustain pedal | pédale de maintien |
| attack / decay / sustain / release | attaque / déclin / maintien / relâchement |
| envelope | enveloppe |
| LFO | LFO |
| oscillator | oscillateur |
| filter / cutoff / resonance | filtre / coupure / résonance |
| drive | saturation |
| voice (polyphony) | voix |
| drum machine / kit / pad | boîte à rythmes / kit / pad |
| synth | synthé |
| reed | anche |
| string (instrument) | corde |
| breath | souffle |
| bellows | soufflet |
| harmonica / harp (harmonica slang) | harmonica |
| hole (harmonica) | trou |
| register (accordion) | registre |
| cassotto | cassotto |
| pipe | tuyau |
| hammer (piano) | marteau |
| damper / pedal | étouffoir / pédale |
| jaw harp | guimbarde |
| mouth | bouche |
| words / lyrics | paroles |
| vocoder | vocodeur |
| granular | granulaire |
| slice (loop) | tranche |
| tap / hold / drag (touch) | toucher / maintenir / glisser |
| click (mouse) | cliquer |
| screen | écran |
| theme (light / dark) | thème (clair / sombre) |
| file / folder | fichier / dossier |
| share | partager |
| latency | latence |
| buffer | tampon |
| metronome / click | métronome / clic |
| count-in | décompte |
| tap tempo | tempo tapé |
| freeze (a clip) | figer |
| stems | pistes séparées |
| automation / lane | automatisation / couloir |
| modifier (input modifiers) | modificateur |
| controller | contrôleur |
| zone (MPE) | zone |
| channel (MIDI) | canal |
| clock (MIDI) | horloge |
| port | port |
| device | appareil |
| phone / tablet / computer | téléphone / tablette / ordinateur |
| realish (picker group) | réalistes (the italic "ish" has no French equivalent) |
