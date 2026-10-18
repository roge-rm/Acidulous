# Cumulus

> Des nappes bâties à partir d’un spectre de partiels.

Cumulus n’utilise pas d’oscillateurs normaux. Vous décrivez un spectre (quels
partiels, à quel volume, de quelle largeur), il en bâtit une table d’ondes et
la joue. C’est pour les nappes, les bourdons et les sons immenses.

## Comment ça marche

Chaque partiel a une largeur au lieu d’être une seule fréquence, ce qui le
rend riche sans avoir besoin d’un chorus.

Bâtir la table prend un moment, alors ça se fait en arrière-plan chaque fois
que vous changez le spectre. Tout à partir de **morph** change tout de suite.

## Commandes du spectre

- **largeur** et **vers l’aigu**, sous **bande** - la largeur de chaque partiel,
  et si les aigus sont plus larges que les graves. C’est la commande
  principale.
- **pente** - l’équilibre entre graves et aigus.
- **étirer** - éloigne les partiels des multiples entiers, comme le haut d’un
  piano ou une cloche. Un peu suffit.
- **prof.** et **chaque**, sous **festons** - creusent des encoches régulières
  dans le spectre.
- **voyelle** et son intensité - donnent au spectre la forme d’une voyelle.
- **impair/pair** - les partiels impairs contre les pairs. Tout impair sonne
  comme une clarinette, et tout pair sonne creux.
- **graine** - les phases aléatoires. Changez-la pour une autre version du
  même son.

## Commandes de jeu

**combien**, **désaccord** et **écart** empilent des copies, et **dérive**, sa
**vitesse** et **épars** gardent le son en mouvement. Ensuite viennent un
filtre avec sa propre enveloppe, une enveloppe d’ampli et une saturation.

## Macros

La section **jeu** commence par huit boutons macro. Un patch peut les nommer
(Éclat, Épanouir, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La section **macros** règle ce que fait chacune. Une macro a quatre routes,
une destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur va de 24 demi-tons à 1. Les commandes
du nuage et du morph vers refont la table, elles ne peuvent donc pas être des
destinations. Touchez le nom d’une macro pour la renommer.

## Astuces

- Utilisez de longues attaques et de longs relâchements et laissez les notes
  se chevaucher.
- Bougez **morph** pendant que vous jouez plutôt que les commandes du spectre,
  car le morph change tout de suite.
