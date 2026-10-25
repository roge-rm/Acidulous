# Pollen

> Des nuages granulaires tirés d’un fichier ou de l’entrée en direct, dont les grains peuvent en faire naître d’autres.

Pollen joue un son comme un nuage de grains courts. La source est un fichier
que vous chargez ou l’entrée en direct, enregistrée dans une boucle pendant que
vous jouez.

## Le nuage

- **taille** et **· écart** - la durée de chaque grain, et combien elle varie.
- **densité** et **· gigue** - combien de grains par seconde, et à quel point
  ils sont irréguliers.
- **fenêtre** et **biais** - la forme de l’entrée et de la sortie en fondu de
  chaque grain.
- **position**, **balayage** et **dispers.** - d’où viennent les grains dans le
  tampon, si ce point bouge et jusqu’où ils s’éparpillent autour.
- **aimant attaques** - aligne les grains sur les attaques de la source, pour
  qu’un matériau rythmique le reste.
- **écart** et **largeur**.

## L’entrée en direct

**lit depuis** passe à l’entrée. **longueur** règle ce qui est gardé, **direct**
le fige et **capture** saisit ce qu’il contient. Un tampon en direct n’est pas
sauvegardé avec le morceau et reste muet dans un export, et le panneau vous le
rappelle.

## La pollinisation

**éclosion** et **prof.** laissent les grains en faire naître d’autres à une
position et une hauteur voisines, jusqu’à la profondeur choisie. Un peu épaissit
le son, et beaucoup fait d’une note une texture changeante. **dérive** et
**muter** règlent jusqu’où les nouveaux grains s’éloignent.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Plus dense,
Dispersion, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace les grains nés ensuite, 24
demi-tons à 1. La source, le tampon, le gel, la capture, la fenêtre, le choix
de dispersion, la gamme, la tonique, la profondeur, le type de filtre, les voix
et l’accordage ne sont pas accessibles. La taille, la place, la hauteur et le
panoramique d’un grain sont fixés à sa naissance, donc ils changent à mesure
que de nouveaux grains arrivent ; le reste bouge tout de suite. Touchez le nom
d’une macro pour la renommer.

## Astuces

- La densité et la taille s’opposent. De longs grains à forte densité font un
  mur, et des courts font une texture.
- Utilisez **aimant attaques** sur tout ce qui a un rythme.
- La dispersion de hauteur peut être verrouillée sur une gamme pour que le
  nuage reste dans la tonalité.
- En qualité **légère** (**Réglages**), le nuage utilise deux fois moins de
  grains.
