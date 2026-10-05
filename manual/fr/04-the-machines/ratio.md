# Ratio

> La FM à six opérateurs, avec un bouton qui passe d’un algorithme à un autre.

Ratio est le synthé FM. Il a six opérateurs, chacun une onde sinus qui est soit
une porteuse (vous l’entendez), soit un modulateur (il change le son d’un
autre). La façon dont ils sont reliés est l’algorithme.

## Les algorithmes qui se transforment

Vous choisissez deux algorithmes, `algoa` et `algob`, et **morph** passe de
l’un à l’autre. Les réglages intermédiaires ne sont dans aucune liste
d’algorithmes standard, et vous pouvez automatiser le morph : une nappe peut
ainsi s’ouvrir de deux opérateurs à six en quelques mesures.

## Les opérateurs

Chacun des six a :

- **ratio** - sa fréquence en multiple de la note. **hauteur** sur **Hz** la
  garde plutôt sur une seule fréquence, ce qui est bien pour les formants.
- **niveau** - combien de FM il ajoute comme modulateur, ou son volume comme
  porteuse.
- **réinj.** - une réinjection sur lui-même, d’un sinus vers une dent de scie.
- **A D S R** - sa propre enveloppe. Sur un modulateur, elle façonne le timbre
  plutôt que le volume.
- le suivi de **clavier**, l’accord **précis**, le **pano** et le **mode**.

## Tout le reste

Un filtre avec sa propre enveloppe, trois enveloppes de plus pour la
modulation, trois LFO qui peuvent se synchroniser au tempo et dix rangées de
matrice (source, deuxième source, destination, profondeur) qui marchent comme
celles de Trinity.

**aimant** garde les ratios sur des valeurs utiles (nombres entiers, nombres
impairs, demi-tons, partiels de cloche), et **biais** les éloigne tous ensemble
de ces valeurs.

## Astuces

- Les ratios entiers (1, 2, 3) sonnent harmoniques, et les autres (1.41, 3.14)
  sonnent comme des cloches et du métal. Les bons sons mêlent souvent les deux.
- Donnez aux modulateurs des enveloppes plus courtes qu’aux porteuses. Une
  attaque brillante qui s’éteint dans un corps doux, c’est le piano électrique
  classique.
- La réinjection sur l’opérateur du haut d’une pile est la façon la moins
  coûteuse d’ajouter de la brillance.
