# Cipher

> Un vocodeur où vous pouvez réarranger quelle bande commande quelle autre.

Cipher est un vocodeur. Il découpe un son en bandes de fréquences et met cette
forme sur un autre son. Vous pouvez aussi changer quelle bande commande
laquelle, et c’est de là que viennent les sons inhabituels.

## Les deux côtés

La **modulante** est le son analysé, en général une voix venant de l’entrée en
direct. La **porteuse** est le son façonné. Cipher a sa propre porteuse
intégrée (un oscillateur avec **désaccord**, **largeur**, **sub**, **bruit** et
**satur.**), alors vous n’avez pas besoin d’une deuxième piste.

## Les bandes

- **bandes** - combien. Moins sonne robotique mais clair, et plus est plus
  doux mais moins clair.
- **grave**, **aigu** et **pente** - la plage couverte par les bandes et leur
  répartition.
- **largeur**, sous **banque** - l’étroitesse de chaque bande.

## La carte des bandes

Normalement, la bande 1 commande la bande 1. **réassign.** change ça :
inversez l’ordre pour que les sons brillants sortent sombres, étalez-la plus
large, figez la forme actuelle avec **figer** pendant que la porteuse joue,
ou fondez les bandes voisines ensemble avec **flou**.

**l’entrée**, **sec** et **traité** règlent quel côté est lequel et combien
vous entendez de chacun.

Deux réglages gardent les consonnes. **sourdes** change la porteuse en bruit
pendant que la voix est sur un « s », un « f » ou un souffle, pour que les
bandes aient du souffle à façonner et que les mots restent clairs. Le groupe
**consonnes** ajoute son propre souffle par-dessus dès que le haut de la voix
devient fort : **détecter** règle la facilité, **au-dessus** où, **niveau**
le volume.

**attaque**, **relâche** et **flou** règlent la vitesse à laquelle chaque bande
suit. Rapide est clair mais peut crépiter, et lent est doux mais peut
bafouiller.

## Modulation

Deux enveloppes et deux LFO qui peuvent se synchroniser au tempo, vers huit
rangées de matrice.

Trois sources viennent de la modulante elle-même : son niveau (**fort**), sa
brillance (**brillant**) et sa **hauteur** (la hauteur demande **suivre**
activé). Par exemple, le niveau sur **flou** resserre les mots à mesure qu’ils
deviennent plus forts.

Les destinations sont les commandes de la carte (**décalage**, **étirer**,
**réassign.**, **figer**, **flou**, **q** et les bords des bandes), plus la
hauteur et le mélange de la porteuse.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Décalage,
Flou, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur fait 24 demi-tons à 1. L’attaque et
le relâchement de l’ampli sont lus au début d’une note, donc ils changent à la
note suivante ; le reste bouge tout de suite. Les interrupteurs, le nombre de
bandes, l’ordre, les ondes, la matrice et ses enveloppes et LFO ne sont pas
accessibles. Touchez le nom d’une macro pour la renommer.

## Astuces

- Montez **sourdes** jusqu’à ce que les mots soient clairs, puis redescendez
  jusqu’à ce que ça arrête de siffler.
- Figez sur une voyelle et jouez des accords dessous pour une nappe vocale.
- Utilisez moins de bandes que vous pensez. Seize bat souvent quarante pour
  les mots.
