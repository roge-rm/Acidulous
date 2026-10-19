# Formulate

> Un synthé 8 bits, et une forme d’onde que vous tapez comme une formule.

Formulate est un synthé de puce sonore avec impulsion, triangle et bruit, comme
les vieilles consoles de jeu. Il peut aussi jouer une forme d’onde que vous
écrivez comme une formule.

## La puce

- **onde** - impulsion, triangle, dent de scie, bruit ou rien.
- **rapport** - la largeur d’impulsion. **pwm** et sa **vitesse** la balaient.
- **sous-oct.** - ajoute une octave en dessous.
- **bruit** - fait passer le bruit d’un souffle à un bourdonnement métallique.
- **bits**, **fréq. éch.** et **lisse** - la résolution, la fréquence
  d’échantillonnage et à quel point les marches sont adoucies.

## Tables de tracker

Il y en a trois, **arpège**, **rapport cyclique** et **volume**, tapées en
texte et parcourues à chaque tick comme un instrument de tracker. Une table
d’arpège `0 4 7` jouée vite fait un accord avec une seule voix.

## La formule

**formule** est une expression en nombres entiers, calculée à chaque
échantillon pour chaque voix, écrite comme en C. Elle peut lire **t**, un
compteur qui va plus vite pour les notes aiguës quand **t suit** est sur par
note, **x**, l’échantillon de la puce elle-même, de 0 à 255 avec 128 au centre,
la note **n**, sa fréquence **f**, la vélocité **v**, le pas de table **s** et
**a**, **b** et **c**. `rnd()` donne un nombre au hasard et `sin()` un sinus de
-127 à 127 tous les 256 pas de **t**. Le résultat est gardé entre 0 et 255,
alors une formule qui passe sous 0 repart du haut. `sin(t) + 128` est un sinus,
`t & 255` une dent de scie et `x & (a | 15)` retire des bits à l’onde de la
puce. Avec **vitesse** à 0,293, une formule de 256 pas joue la note que vous
touchez.

**contre x** règle la rencontre de la formule et de l’échantillon de la puce :
non, remplacer, anneau (les deux multipliés), Gate (la puce joue tant que la
formule dépasse la moitié) ou xor. **intensité** passe de la puce seule au
résultat. Si une formule a une erreur, le panneau vous le dit.

## Enveloppe de filtre

La section **forme** a une enveloppe sur le filtre. **intensité** déplace la
coupure jusqu’à 6 octaves au sommet de l’enveloppe, vers le haut ou vers le
bas, et **A**, **D**, **S** et **R** la façonnent. À 0, le filtre reste là où
**coupure** le met. Pour une basse pincée, partez d’une coupure basse, d’une
intensité de 3 ou 4 et d’un déclin court.

## Macros

La section **Puce** commence par huit boutons macro dans une carte **jeu**. Un
patch peut les nommer (Timbre, Grain, etc.) et les noms s’affichent sur les
boutons, dans les lanes d’automation et dans la liste de mappage du
contrôleur. Ils sont à zéro au chargement d’un patch, ce qui donne le patch
tel qu’il a été fait. Ils sont distincts de **a**, **b** et **c**, mais une
macro peut aussi tourner ceux-là.

La section **macros** règle ce que fait chacune. Une macro a quatre routes,
une destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur va de 24 demi-tons à 1. Touchez le nom
d’une macro pour la renommer.

## Astuces

- Utilisez **a** dans la formule et automatisez-le. `x & a` avec **a** sur
  un couloir balaie le timbre.
- Les tables de tracker changent beaucoup de choses. Un son simple avec une
  bonne table de volume bat souvent une formule astucieuse sans table.
