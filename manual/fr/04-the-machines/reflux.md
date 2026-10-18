# Reflux

> La basse acid : un oscillateur, un filtre qui hurle, et des lignes que vous jouez.

Reflux, c’est un oscillateur, un filtre et une enveloppe, réglés pour que vous
puissiez jouer une ligne de basse au lieu de la construire pas à pas.

## L’accent et le glissé

Les lignes de basse acid viennent de l’accent et du glissé, et Reflux les tire
de votre jeu :

- L’accent, c’est la vélocité. Jouez une note plus fort et elle est plus forte,
  plus brillante et a plus d’enveloppe de filtre.
- Le glissé, c’est le legato. Faites chevaucher deux notes et la deuxième
  glisse depuis la première au lieu de repartir. Laissez un espace et elle est
  pincée.

Jouez donc une ligne au clavier, ou dessinez-la dans la grille avec des notes
qui se chevauchent pour les glissés.

## Les commandes

La section **son** en a l’essentiel :

- **osc** - l’**onde**, scie ou impulsion, avec **mélange** pour aller vers
  l’autre onde, **pw** pour la largeur d’impulsion, un oscillateur **sub** une
  octave plus bas et **accordage**.
- **filtre** - **coupure** et **rés.**, **mod env** pour l’ouverture du filtre
  par l’enveloppe et **déclin** pour sa durée. Les déclins courts sont pincés
  et les longs sont élastiques. **mode** est passe-bas ou passe-bande, et en
  passe-bas **type** choisit LP24 (l’habituel), LP12, HP ou **réject.**
  (coupe-bande). L’essentiel du son est ici.
- **toucher** - **accent** est ce qu’une note accentuée ajoute au niveau et au
  filtre, **glissé** est la durée d’un glissé, et **vél.** est la part de la
  vélocité dans le niveau. Une note qui glisse garde le niveau de la note d’où
  elle vient.
- **sortie** - **satur.**, d’où vient le hurlement, et **volume**.

La section **LFO** est un LFO avec une **vitesse**, ou une valeur de note avec
**synchro**, une **forme** (sinus, triangle, carré, scie ou aléatoire) et
**redémarrer**, libre ou à chaque note. Il fait bouger la **coupure**, la
**hauteur** et **pw**, chacune dans un sens ou l’autre à partir de zéro.
Synchronisé sur les croches et redémarré à chaque note, c’est une basse
wobble.

## Macros

La section **jeu** a huit boutons macro. Un patch peut les nommer (Ouvert,
Squelch, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La section **macros** règle ce que fait chacune. Une macro a quatre routes,
une destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur va de 24 demi-tons à 1 et la vitesse
du LFO de 4 octaves. Touchez le nom d’une macro pour la renommer.

## Astuces

- La résonance et la saturation ensemble font le son classique. La plupart des
  sons acid ont les deux montées et la coupure qui bouge.
- Enregistrez le bouton de coupure en automatisation pendant que le morceau
  joue, plutôt que de la dessiner.
- Deux Reflux qui jouent le même clip, l’un pincé et l’autre glissé, séparés
  dans le panoramique avec des coupures un peu différentes, ça marche encore
  très bien.

*Reflux s’appelait Subvert dans les versions antérieures, et les morceaux et sons de cette époque s’ouvrent en Reflux.*
