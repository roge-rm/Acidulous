# Deesser
> Baisse une bande quand elle devient trop forte : un s, une cymbale.

Écoute une bande de fréquences et la baisse quand elle passe le seuil. Fait
pour le sifflement d’un s sur une voix, et tout aussi bon sur des cymbales
dures.

## Les réglages

- **fréq.** : le milieu de la bande, de 2 à 12 kHz.
- **seuil** : là où la bande commence à baisser.
- **plage** : de combien elle baisse au plus.
- **mode** : **partage** ne baisse que la bande. **large** baisse tout le son,
  ce qui est plus doux sur une voix avec beaucoup d’air.
- **écouter** : ne joue que la bande, pour trouver où se trouve le s.
- **relatif** *(extra)* : mesure la bande par rapport au reste du son plutôt
  qu’au niveau maximal, donc une phrase douce et une forte sont traitées
  pareil. Le seuil devient alors de combien la bande peut monter sous le
  reste : un s est presque tout en bande, une voyelle l’a 20 dB plus bas ou
  plus. À -36, c'est 10 dB.
- **gain** : le niveau de sortie.

### Renvoi

- **renvoi** *(extra)* : envoie ce que le deesser enlève dans son propre
  écho, donc chaque s revient en une traînée brillante pendant que la voix
  reste propre. Baissé, les échos déjà envoyés finissent de sonner.
  **écouter** le fait taire.
- **temps** : l’écart entre les échos, sur le tempo : 1/16, 1/8, 1/8 pointée,
  1/4, 1/4 pointée ou 1/2. Les répétitions vont de gauche à droite, donc une
  piste mono sort en stéréo.
- **réinjection** : combien de temps les échos reviennent.

## Astuces

- Activez **écouter**, déplacez **fréq.** jusqu’à ce que le s soit le plus
  fort, puis coupez **écouter**.
- Avec **relatif**, la voix peut s’éloigner du micro et il attrape toujours
  le même s.
- Montez **renvoi** sur le dernier mot d’une phrase, comme on lance un mot
  dans l’écho en dub : seuls ses s s’envolent. Essayez **Dub Sparkle**.
