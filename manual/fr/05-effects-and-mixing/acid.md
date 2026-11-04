# Acid
> Le filtre de Reflux sur n’importe quelle piste, balayé par ses coups ou par
> un motif.

## Les réglages

- **coupure** : où le filtre se pose, de 40 Hz à 12 kHz.
- **résonance** : combien il sonne. Vers le haut, il hurle.
- **env** : jusqu’où chaque balayage l’ouvre, jusqu’à quatre octaves.
- **déclin** : la vitesse à laquelle il retombe, de 30 ms à 3 s.
- **accent** *(extra)* : de combien un balayage accentué va plus loin, et
  combien il est plus fort.
- **motif** *(extra)* : ce qui lance le balayage. **piste**, c’est la piste
  elle-même : chaque coup l’ouvre, et un coup fort est un accent. Les huit
  autres sont des motifs de seize pas sur le tempo, avec des accents.
- **satur.** : de la saturation après le filtre.
- **mode** : passe-bas ou passe-bande.
- **mélange** : le son sec face au son filtré.
- **gain** : le niveau de sortie.

### Tonalité

- **suivre** *(extra)* : jusqu’où la coupure quitte son bouton pour se poser
  sur une note de la tonalité. Tout en haut, avec la résonance haute, le
  filtre chante la note et chaque balayage monte à partir d’elle. À 0, la
  tonalité ne fait rien.
- **de** : d’où viennent les notes. **gamme** prend une note à chaque pas
  ou coup dans **tonalité** et **gamme**, surtout la fondamentale, comme une
  ligne de basse. **notes** prend les notes jouées sur la piste : avec
  **motif** à **piste**, c’est la note qu’on vient de jouer, et sur un motif
  chaque pas prend la note tenue suivante, vers le haut. Seul l’insert
  d’une piste a **de** : sur un groupe, le master ou l’entrée, c’est
  toujours la gamme.
- **tonalité** et **gamme** : la fondamentale de la gamme et son genre.
- **octave** : déplace la note vers le haut ou le bas, de -2 à +3.

### Glissé

- **porta.** : la durée d’un glissé, de 10 ms à 1 s.
- **glissés** *(extra)* : combien de pas ou de coups glissent vers leur note
  au lieu de sauter, les pas glissés du motif d’abord. Avec **de** à
  **notes** et les coups de la piste, une note jouée pendant qu’une autre est
  tenue glisse toujours.

## Astuces

- Sur un accord tenu avec **motif** à **1/16**, une nappe devient une ligne
  acide.
- Sur une boucle de batterie avec **motif** à **piste**, chaque coup aboie.
- En passe-bande avec la piste comme motif, c’est une wah qui suit le jeu.
- Pour une boucle de batterie qui hurle une ligne mineure, mettez **suivre**
  tout en haut, **résonance** presque en haut et **env** bas, pour qu’on
  entende la note par-dessus le balayage.
