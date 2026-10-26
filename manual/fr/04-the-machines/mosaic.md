# Mosaic

> Un lecteur multi-échantillons : des zones sur le clavier et la vélocité, plus des nuages de grains.

Mosaic joue des cartes d’échantillons. Chaque échantillon (une zone) couvre une
plage de touches et de vélocités, et ensemble ils forment un instrument jouable.

## La carte

Une **zone** est un fichier, une plage de touches, une plage de vélocités, une
note de base et un accordage. Construisez les zones à la main avec vos propres
enregistrements, ou chargez une **soundfont…** pour obtenir toute une carte
d’un coup.

- **fondu clav.** et **fondu vél.** - des fondus enchaînés entre zones
  voisines, pour ne pas entendre les raccords.
- **balayage** et **intensité** - parcourent les couches de vélocité avec un
  bouton plutôt qu’avec la vélocité. Mettez le balayage sur la molette de
  modulation pour mêler un échantillon doux et un fort.

## Le jeu

**début**, **boucle**, **sens**, **env de** et **mod fichier** décident de la
façon dont chaque fichier est joué. Ensuite viennent un filtre avec sa propre
enveloppe et son suivi de clavier, deux enveloppes, deux LFO, le portamento et
les modes de voix.

## Les grains

**nuage** joue plutôt la carte comme un nuage de grains, avec **taille**,
**densité**, **position**, **dispers.**, **hauteur** et **vitesse**.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Plus clair,
Montée, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur fait 24 demi-tons à 1. **mod 1** à
**mod 8** sont les profondeurs des rangées de la page mod, donc une macro peut
faire entrer une rangée qu’un patch a préparée à zéro. Le balayage, le départ
et les enveloppes sont lus au début d’une note, donc ils changent à la note
suivante ; le reste bouge tout de suite. Les interrupteurs, la boucle, le type
de filtre, le mode de voix, l’accordage, le panoramique, les enveloppes de
modulation et les formes des LFO ne sont pas accessibles. Touchez le nom
d’une macro pour la renommer.

## Astuces

- Réglez les fondus avant de juger le son d’une carte.
- Vérifiez les notes de base. Une mauvaise base désaccorde tout.
- Les grosses cartes prennent beaucoup de mémoire. La bibliothèque de sons
  montre ce qu’un morceau utilise avant que vous supprimiez quoi que ce soit.
