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
vous entendez de chacun. **sourdes** s’occupe des consonnes pour que les mots
restent clairs.

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

## Astuces

- Montez **sourdes** jusqu’à ce que les mots soient clairs, puis redescendez
  jusqu’à ce que ça arrête de siffler.
- Figez sur une voyelle et jouez des accords dessous pour une nappe vocale.
- Utilisez moins de bandes que vous pensez. Seize bat souvent quarante pour
  les mots.
