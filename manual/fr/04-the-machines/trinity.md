# Trinity

> Trois oscillateurs, des tables d’ondes, deux filtres et une matrice de modulation : le polyvalent.

Trinity est le synthé polyphonique à tout faire, trois oscillateurs dans deux
filtres avec une matrice de modulation par-dessus.

## Les oscillateurs

Chaque oscillateur joue une table d’ondes, une rangée de formes d’onde que
**pos** parcourt. Automatisez **pos** pour balayer le timbre.

**onde** choisit dent de scie, carré, triangle ou sinus, ou l’une des 32 tables
d’ondes de huit formes chacune : des tables additives simples (Creux, Pile,
Pair), des balayages de filtre et de synchro (Réso, Synchro), des formes FM et
courbées (Indice, Anche, Courbé), des voix (Voyelle, Chœur, Nasal), des
instruments (Archet, Cuivre, Pincé, Orgue, Cloche) et des tables numériques et
étranges (Écrasé, Escalier, Puce, Poussière, Spirale).

- **densité** empile des copies de l’oscillateur, **désaccord** les écarte et
  **dérive** fait errer un peu chaque copie. C’est ainsi qu’on obtient un gros
  son de supersaw.
- **dur** est la synchro d’oscillateur et **pw** la largeur d’impulsion.
- **fm21** et **fm32** sont de la FM entre les oscillateurs (2 dans 1, 3 dans
  2), pour des sons métalliques et de cloche.
- **bruit** et sa **couleur** servent au souffle, aux queues de caisse claire,
  etc.

## Les filtres

Il y en a deux. **routage** les met en série, en parallèle ou partagés avec une
**balance** entre eux, et chacun a son propre type, sa saturation, son suivi de
clavier et sa quantité d’enveloppe.

## La modulation

Six enveloppes et trois LFO alimentent douze rangées de matrice.

L’enveloppe **env ampli** est l’amplitude et **env filtre** est le filtre, et
les quatre autres ne font rien tant que vous ne les routez pas. Chaque LFO a une
onde, une vitesse qui peut se synchroniser au tempo, un délai, une phase, une
synchro au clavier, un mode coup unique et un lissage.

Une rangée de matrice a une source, une deuxième source, une destination et une
profondeur. La deuxième source multiplie la première : une enveloppe avec la
molette de modulation comme deuxième source est une enveloppe que vous pouvez
faire entrer avec la molette.

Deux sortes de destination changent la vitesse d’un LFO. **l1vit** (et l2, l3)
la change en douceur, jusqu’à quatre octaves dans chaque sens. **l1pas** la
change par octaves entières, une par quart de profondeur. Un LFO synchronisé
passe de 1/16 à 1/32 et ainsi de suite et reste sur le temps, puisqu’il change
au début d’un cycle, et un LFO libre double ou divise par deux sa vitesse.

La molette de modulation ouvre aussi les deux filtres, quoi que dise la
matrice, et **molette** dans la section voix règle de combien. À zéro, la
molette ne fait que ce que la matrice lui dit.

## Macros

La section **jeu** a huit boutons macro. Un patch peut les nommer (Ouvert,
Mordant, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La section **macros** règle ce que fait chacune. Une macro a quatre routes,
une destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton ajoute bouton fois quantité à la destination, comme une
rangée de matrice dont le bouton serait la source. Touchez le nom d’une macro
pour la renommer.

## Astuces

- Commencez par les oscillateurs (position dans la table et densité) plutôt que
  par le filtre. C’est là qu’est le caractère de Trinity.
- Une forte densité coûte le plus de CPU. Si un morceau peine, figez le clip de
  Trinity qui a la plus grosse pile.
