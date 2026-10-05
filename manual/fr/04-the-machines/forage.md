# Forage

> Une boîte à rythmes à échantillons : treize pads pour vos propres sons.

Forage joue vos propres échantillons sur treize pads, chacun avec un peu de
traitement pour l’aider à trouver sa place.

## Un pad

- **niveau**, **pano**, **hauteur** et **déclin**.
- **début** et **fin** - la partie du fichier qui joue, alors un long
  enregistrement peut alimenter plusieurs pads.
- **jeu** - une fois, en boucle ou tenu.
- **inverser** - vers l’avant ou à l’envers.
- **coupure**, **rés.** et **mode** (passe-bas ou passe-bande), et **écraser**
  pour réduire les bits.
- **env hauteur** et son **déclin**, sous **punch** - une chute de hauteur,
  pour qu’un échantillon tombe comme le fait un tambour.
- **étouffe** - les pads d’un même groupe d’étouffement se coupent entre eux,
  comme un charleston ouvert qui s’arrête quand le fermé joue.

## Découper un seul fichier

Il y a un quatorzième emplacement au-dessus des pads pour un fichier partagé
par toute la machine. Chargez-y un break, demandez treize tranches et chaque
pad en joue un morceau.

## Astuces

- Mettez vos charlestons dans un groupe d’étouffement.
- Coupez le silence au début d’un échantillon, sinon il jouera en retard.
- Automatisez **début** sur une mesure pour changer un coup en bégaiement.
- La bibliothèque de sons vous avertit avant de supprimer un fichier qu’une
  piste utilise encore.
