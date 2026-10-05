# Gate
> Une porte de bruit, avec un filtre sur ce qu’elle écoute et une entrée latérale.

## Les réglages

- **seuil** : le niveau où elle s’ouvre, de -80 à 0 dB.
- **hyst.** : de combien le signal doit descendre sous le seuil avant qu’elle
  se ferme, jusqu’à 24 dB, pour qu’elle ne clignote pas sur une note proche du
  seuil.
- **attaque** : la vitesse d’ouverture, de 0,05 à 50 ms.
- **tenir** : combien de temps elle reste ouverte après la baisse du signal,
  jusqu’à 500 ms.
- **relâche** : la vitesse de fermeture une fois le maintien fini.
- **atténuer** *(extra)* : jusqu’où descend la porte fermée. À fond, c’est une
  porte complète, et vers -12 dB, ça convient à la batterie.
- **clavier** *(extra)* : un filtre passe-haut sur ce que la porte écoute, pas
  sur le son. Montez-le pour que la porte ignore le ronflement et le
  grondement mais s’ouvre encore pour les notes.
- **entrée lat.** : ce qui l’ouvre, **propre** ou une autre piste. Le filtre
  **clavier** s’applique quand même.

Il n’y a pas de réglage de mélange, puisqu’une porte à moitié ouverte ne serait
que le bruit à moitié du niveau.

## Astuces

- Si elle claque, allongez **tenir** avant de toucher à la relâche.
- Pour une guitare avec un ronflement du secteur, réglez **clavier** vers
  120 Hz.
- Déclenchez la porte d’une nappe tenue par la piste de charleston pour la
  hacher en rythme, avec **atténuer** vers -12 dB pour que les trous ne soient
  pas silencieux.
- Avant un ampli, elle enlève le souffle du micro de guitare, et après, celui
  de l’ampli. Sur un emplacement d’entrée, elle nettoie l’enregistrement
  lui-même.
