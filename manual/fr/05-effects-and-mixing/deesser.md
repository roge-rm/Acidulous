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
  plus. Essayez -10.
- **gain** : le niveau de sortie.

## Astuces

- Activez **écouter**, déplacez **fréq.** jusqu’à ce que le s soit le plus
  fort, puis coupez **écouter**.
- Avec **relatif**, la voix peut s’éloigner du micro et il attrape toujours
  le même s.
