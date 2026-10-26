# Dice

> Un découpeur de boucles avec du hasard sur chaque tranche.

Dice coupe une boucle en tranches et les rejoue, avec une chance que quelque
chose de différent arrive sur chacune. C’est bon pour les breaks.

## Découper

**combien** règle le nombre de morceaux, et **selon** règle où ils tombent,
des divisions égales jusqu’aux coups de la boucle elle-même. Chaque tranche a
ses propres **niveau**, **pano**, **hauteur**, **déclin** et **jeu**.

## Les dés

Cinq chances, tirées sur chaque tranche :

- **échanger** - jouer une autre tranche.
- **inverser** - la jouer à l’envers.
- **bégaie**, avec **fois** - répéter son début autant de fois dans sa propre longueur.
- **omettre** - ne rien jouer.
- **saut**, avec **jusqu’à** - la jouer plus haut ou plus bas d’autant de demi-tons.

**graine** décide des tirages et **dés** sur **tenir** les fige. Montez les dés jusqu’à
entendre quelque chose qui vous plaît, puis figez-le.

## Jouer

Les tranches sont sur la grille comme des sons de batterie, alors vous pouvez
réordonner la boucle en écrivant un motif. **vitesse**, **gate**, **hauteur**,
**précis** et **accent** règlent la lecture, suivis d’un filtre et d’une
saturation.

## Tempo

**joue au** décide si la boucle garde son propre tempo ou suit le morceau. Sur
**morceau**, le réglage par défaut, chaque tranche est étirée au tempo du
morceau sans changer sa hauteur, alors un break à 90 bpm entre dans un morceau
à 126 bpm sans trous. **hauteur** ne change alors que la hauteur, et
**vitesse** l’accélère ou la ralentit encore.

**mesures** est la longueur de la boucle, d’où son tempo est calculé. Sur
**auto**, il est deviné d’après la longueur de la boucle et la place de ses
coups, et la ligne sous le nom de la boucle dit ce qu’il a trouvé, comme
« 2 mesures à 90 bpm ». Si c’est faux, réglez-le vous-même.

Une tranche jouée à l’envers n’est pas étirée. Elle est accélérée ou ralentie
comme une bande, alors elle se désaccorde un peu quand le morceau est loin du
tempo de la boucle.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Chaos,
Bégaie, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur fait 24 demi-tons à 1. Les cinq dés,
fois, jusqu’à, graine et tranches y sont tous, alors un seul bouton peut mener
une boucle de droite à folle, ou passer d’un motif figé à l’autre. Tout sauf le
filtre, la saturation, le volume et le pano est lu au début d’une tranche, donc
ça change à la tranche suivante. Les réglages par tranche, selon, tenir, joue
au, mesures et le type de filtre ne sont pas accessibles. Touchez le nom d’une
macro pour la renommer.

## Astuces

- Non figé, Dice change à chaque mesure. Figé sur un bon tirage, il devient une
  partie.
- Pour un break qui swingue, coupez sur les coups plutôt qu’également.
- La graine le rend répétable, alors un export sonne comme ce que vous avez
  arrangé.
