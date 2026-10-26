# Molt

> Chantez une prise, et la grille de notes l’accorde.

Molt fait d’un enregistrement chanté un instrument. Il trouve les impulsions
séparées de la voix et les espace autrement pour changer la hauteur, ce qui
garde le caractère de la voix au lieu de la rendre nasillarde comme un
écureuil.

## La grille accorde

Dessinez les notes voulues dans la grille de notes, chantez n’importe quoi, et
la prise est tirée vers ces notes.

- **intensité** - la force de la traction. Au maximum, c’est l’accord dur, et
  moins donne une correction douce.
- **vitesse** - à quelle vitesse la note est atteinte. Lent sonne comme un
  glissé naturel, rapide sonne par paliers.
- **robot** - aplatit complètement la hauteur sur la note.
- **porta.** - le temps que met une note pour glisser depuis la note la plus
  proche qui sonne encore. Les notes d’un accord ne glissent pas l’une vers
  l’autre.

## La voix

- **formant** - déplace les formants sans changer la hauteur, pour que la voix
  semble plus grosse ou plus petite.
- **mega** - un mégaphone.
- **début** et **boucle** - quelle partie de la prise joue, et si elle boucle.

Puis un filtre, une enveloppe d’amplitude et une saturation.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Plus grand,
Tirer, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur fait 24 demi-tons à 1. Le début et la
vélocité sont lus au début d’une note, donc ils changent à la note suivante ;
le reste bouge tout de suite. Robot, boucle, portamento, le type de filtre, le
bend, l’octave et les demi-tons ne sont pas accessibles. Touchez le nom d’une
macro pour la renommer.

## Astuces

- Gardez le grondement grave hors de l’enregistrement. Le bruit du téléphone
  tenu dans la main peut tromper la détection de hauteur. Le **coupe-bas** de
  l’enregistreur aide, et il est activé par défaut pour Molt.
- Les consonnes sont copiées plutôt que transposées, et restent nettes.
- Chanter approximativement et laisser la grille corriger marche très bien.
