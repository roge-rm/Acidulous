# Spectral
> La piste décomposée en fréquences et remise ensemble.

Environ quarante fois par seconde, Spectral sépare le son en ses fréquences,
les modifie et le reconstruit. Sans rien tourner, ce qui sort est ce qui est
entré.

## Les réglages

- **figer** *(extra)* : **tenir** garde le son tel qu’il est à cet instant,
  un accord ou une voyelle qui dure tant qu’il est tenu. **marche** le
  relâche.
- **traîne** : laisse chaque fréquence s’éteindre au lieu de s’arrêter, si
  bien que les notes se fondent. Au maximum, une note traîne des secondes.
- **flou** : brouille le timing dans chaque tranche, pour un son lavé,
  lointain, chuchoté.
- **robot** : jette le timing, pour une voix plate et bourdonnante.
- **crêtes** *(extra)* : ne garde que les fréquences les plus fortes, un son
  vitreux et sifflant. Plus haut, il en reste moins.
- **pente** : penche le tout vers le sombre ou le brillant, autour de 1 kHz.
- **mélange** : la piste sèche face à la piste modifiée.
- **gain** : le niveau de sortie.

### Bégaie

- **bégaie** *(extra)* : **boucle** prend une tranche du son à chaque pas de
  la grille et la tient pendant le pas. Après **longueur** pas, il rejoue ces
  tranches en boucle, chacune avec sa couleur, jusqu’au retour à **marche**,
  où il se fond dans la piste.
- **grille** : le pas, de la noire à la triple croche, avec les triolets.
- **longueur** : combien de pas tient la boucle, de 1 à 16. Montée pendant
  la boucle, elle prend les pas en plus dans la piste.
- **ordre** : **avant**, **inverser**, **aller-retour** ou **aléatoire**.
  L’aléatoire joue le même ordre à chaque fois.
- **fondu** : fait s’éteindre chaque pas dans sa durée, du son tenu à un
  court bip.
- **refaire** : reprend la boucle dans la piste après ce nombre de tours, ou
  **jamais**.

Le son modifié suit la piste avec environ 40 ms de retard. Le son sec de
**mélange** est retardé d’autant, pour que les deux restent ensemble.

## Astuces

- Assignez **figer** à un bouton et tenez un accord sous la partie suivante.
- Assignez **bégaie** à un bouton et tenez-le pour un roulement, ou
  dessinez-le dans une lane d’automation. **Step Repeat** et **Shuffle**
  bouclent d’eux-mêmes et reprennent la boucle tous les un ou deux tours.
- **Robot Voice** sur une piste chantée ou parlée, c’est le robot plat
  classique.
- **Ice** sur une nappe ou un piano laisse un scintillement vitreux de ses
  notes les plus fortes.
