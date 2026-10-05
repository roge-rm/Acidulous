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

## Astuces

- Utilisez de longues attaques et de longs relâchements et laissez les notes
  se chevaucher.
- Bougez **morph** pendant que vous jouez plutôt que les commandes du spectre,
  car le morph change tout de suite.
