# Trinity

> Trois oscillateurs, des tables d’ondes, deux filtres et une matrice de modulation : le polyvalent.

Trinity est le synthé polyphonique à tout faire, trois oscillateurs dans deux
filtres avec une matrice de modulation par-dessus.

## Les oscillateurs

Chaque oscillateur joue une table d’ondes, une rangée de formes d’onde que
**pos** parcourt. Automatisez **pos** pour balayer le timbre.

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

La molette de modulation ouvre aussi les deux filtres, quoi que dise la
matrice, et **molette** dans la section voix règle de combien. À zéro, la
molette ne fait que ce que la matrice lui dit.

## Astuces

- Commencez par les oscillateurs (position dans la table et densité) plutôt que
  par le filtre. C’est là qu’est le caractère de Trinity.
- Une forte densité coûte le plus de CPU. Si un morceau peine, figez le clip de
  Trinity qui a la plus grosse pile.
