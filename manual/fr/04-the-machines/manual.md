# Manual

> L’orgue : deux claviers et un pédalier, quatre modèles et un baffle rotatif.

Manual est un orgue à deux claviers et un pédalier. Ils partagent tous un même
jeu de 91 roues phoniques, comme le vrai : la même roue joue la quinte d’une
note et l’octave d’une autre, et c’est pourquoi les accords d’orgue
scintillent et battent un peu.

## Les tirettes

Neuf par clavier, et elles font l’essentiel du son. Chacune ajoute une
harmonique de la note, aussi fort que vous la tirez.

## Les quatre modèles

**modèle** choisit le genre d’orgue :

- **combo** - un orgue à transistors, bourdonnant, avec sa propre attaque.
- **molette** - l’orgue à roues phoniques classique, avec **fuite** (les roues
  qui débordent l’une sur l’autre), **ronfl.** et **âge**.
- **anche** - un harmonium, avec **pression**, **buzz** et un trémolo.
- **tuyau** - un orgue à tuyaux avec les jeux **principal**, **flûte**,
  **corde**, **anche** et **fourniture**, du **chiff** sur l’attaque et le
  bruit de la **mécanique**.

La fuite, le ronflement et le bruit de soufflerie entrent en fondu quand
l’orgue commence à jouer et sortent environ une demi-seconde après qu’il
s’arrête : une piste qui ne joue pas dans une scène y reste muette.

## Le clic des touches

Sur un vrai orgue à roues phoniques, les contacts des touches se ferment l’un
après l’autre, et chaque note commence par une petite rafale de clics. Dans le
groupe **clic**, **oui** règle son volume, **non** est le clic quand vous
relâchez et **écart** étale les contacts. Sans le clic, ça sonne beaucoup moins
comme un orgue.

## Le baffle

Le haut-parleur rotatif est intégré : un pavillon qui tourne au-dessus d’un
tambour qui tourne dans l’autre sens, chacun avec sa vitesse et sa rampe de
montée et de descente, et deux micros que vous pouvez déplacer. La position des
rotors peut aussi servir de source de modulation.

## La modulation

Deux enveloppes et deux LFO qui peuvent se synchroniser au tempo, vers huit
rangées de matrice (source, destination, profondeur).

Quatre des sources viennent de l’orgue lui-même : **pavillon** et **tambour**
(là où pointent les rotors), le **balayage** du scanner de vibrato, et **vent**
(la pression d’air). Le pavillon et le tambour ne bougent pas quand le rotatif
est arrêté.

Les destinations comprennent chaque tirette, et toutes les tirettes d’un
clavier à la fois.

## La pédale d’expression

**crescendo** dans le groupe **toucher** de la page **sortie** choisit ce qui
actionne la pédale d’expression : rien, la molette de modulation, la pression
ou **péd**, une pédale d’expression sur le CC 11. **péd** reste ouverte tant que
la pédale n’a rien envoyé. Fermée, l’orgue joue au quart de son niveau. Montez **jalousies** et
la fermer rend aussi le son plus sombre, comme les jalousies de la boîte
expressive d’un orgue à tuyaux. À 0, la pédale ne change que le niveau.

## Le portamento des tirettes

**porta. tirettes** sur la page **tirettes** règle le temps que mettent les
tirettes, les tirants, les jeux et le morph pour arriver à un nouveau réglage,
que vous les bougiez à la main ou avec une lane, une macro ou la matrice. À 0,
ils bougent tout de suite. Avec une seconde ou deux, une tirette tirée sous un
accord tenu ouvre le son lentement.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Tirer, Plein,
etc.) et les noms s’affichent sur les boutons, dans les lanes d’automation et
dans la liste de mappage du contrôleur. Ils sont à zéro au chargement d’un
patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. Chaque tirette est une destination. La hauteur
déplace tout l’instrument, 24 demi-tons à 1. Le modèle, les partages, les
interrupteurs et les choix par pas, la matrice et les pas d’accord ne sont pas
accessibles. Un changement d’attaque, de relâchement, de clic, de contacts, de
chiff, de temps ou de suivi de la percussion ou de vélocité s’entend à la note
suivante.
Touchez le nom d’une macro pour la renommer.

## Astuces

- Changez la vitesse du rotatif en jouant. L’accélération et le ralentissement
  sont le meilleur moment.
- La percussion prend la dernière tirette quand elle est active, comme sur les
  originaux. C’est d’habitude mieux de la laisser ainsi.
