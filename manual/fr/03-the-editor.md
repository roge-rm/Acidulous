# L’éditeur
Un clip s’ouvre avec la grille de notes en haut, les commandes de la machine
au milieu et le clavier ou les pads en bas.

## La grille

Pour une machine mélodique, c’est une grille de notes, avec la hauteur à la
verticale et le temps à l’horizontale.

- Touchez une case vide pour ajouter une note, et touchez une note pour la
  supprimer.
- Glissez une note pour la déplacer, ou glissez son bord droit pour changer sa
  longueur.
- Glissez à **deux doigts** pour défiler et pincez pour zoomer. Un doigt
  dessine toujours.
- Le coin **gam** change l’affichage de la gamme du morceau : toutes les notes,
  notes de la gamme en surbrillance ou seulement les notes de la gamme.

Pour une boîte à rythmes, c’est une grille de pas avec une rangée par son.
Touchez pour ajouter un coup.

## Générer

Le bouton du dé en haut de l’éditeur écrit des notes pour vous. Le clip change
à mesure que vous tournez les boutons, pour écouter pendant que le morceau
joue. **OK** garde les notes et **Annuler** remet le clip comme avant, et
annuler reprend le tout en une seule étape.

- **rythme** répartit un nombre de coups le plus également possible sur un
  nombre de pas, et **rotation** fait tourner le motif. Un motif plus court que
  la mesure se décale par rapport à elle.
- **ligne** écrit une ligne dans la gamme de la piste ou la tonalité du
  morceau, ou en pentatonique mineure s’il n’y a ni l’une ni l’autre. **sauts**
  règle à quelle fréquence elle saute au lieu d’aller à une note voisine.
- **muter** change une partie des notes déjà là : certaines bougent d’un degré
  dans la gamme, certaines partent, quelques nouvelles apparaissent et les
  vélocités changent. **quantité** règle combien.

Sur une boîte à rythmes, rythme et **semer** travaillent sur un son et laissent
les autres tranquilles, et muter déplace les coups dans le temps au lieu de la
hauteur.

**relancer** donne une nouvelle série de choix au hasard. Les mêmes réglages et
le même tirage donnent toujours les mêmes notes.

## Verrouiller un pas

Un verrou donne à un pas sa propre valeur pour un bouton, par exemple accorder
la caisse claire du pas 7 plus haut que toutes les autres.

- Touchez le losange (◆) en haut de l’éditeur. Il s’allume, et la grille prend
  un bord rose.
- Touchez les pas à verrouiller : les coups dans la grille de batterie, les
  pas dans la rangée de pas de Reflux ou les notes dans la grille de notes.
  Touchez encore pour en libérer un.
- Tournez n’importe quel bouton du panneau en dessous, celui de la machine ou
  d’un effet. Les pas choisis prennent cette valeur et le bouton ne bouge pas
  pour le reste du clip.
- Maintenez un bouton pour retirer le verrou des pas choisis.
- Touchez encore le losange pour revenir au dessin des notes.

Un verrou dure un pas de la grille, ou la longueur de la note dans la grille
de notes. Les pas verrouillés ont un losange rose dans le coin, et un bouton
qui a des verrous montre ◆ sur son cadran.

Si vous tournez le bouton plus tard sans pas choisi, tous les pas non
verrouillés le suivent. Un bouton qui a déjà un couloir dessiné ne peut pas
être verrouillé, et montre ∿ à la place.

## Enregistrer

Appuyez sur enregistrer, puis jouer, et ce que vous jouez va dans le clip, sur
la grille du clip. Le fonctionnement d’une prise se règle dans Réglages,
**enregistrer** : un décompte, la quantification ou non, l’ajout aux notes déjà
là ou leur remplacement, le départ avec jouer ou à la première note jouée, et
la boucle continue ou l’arrêt après un passage.

Chaque prise est une seule étape d’annulation, du moment où l’enregistrement et
la lecture tournent tous les deux jusqu’à ce que l’un s’arrête.

Le clavier joue plus doux en bas d’une touche et plus fort en haut. Le bouton
de la barre du bas désactive ça pour que chaque note soit à pleine vélocité,
et les pads de batterie ont leur propre réglage pour ça.

Glissez vers le haut ou le bas la rangée de commandes juste au-dessus des
touches pour agrandir ou réduire le clavier.

## Quantifier

**⊞** dans l’en-tête (ou **Q**) ouvre la fenêtre de quantification. Elle agit
sur les notes choisies, ou sur tout le clip si aucune ne l’est, et vous
entendez les changements à mesure. **OK** les garde en une seule étape
d’annulation et **Annuler** remet tout comme avant.

- **grille** - la grille du clip, ou une autre.
- **force** - jusqu’où chaque note avance vers la grille. Moins que tout le
  chemin resserre une partie et garde son feeling.
- **déplacer** - seulement les débuts, ou aussi les fins.
- **tel que joué** - remet les notes exactement là où elles ont été jouées.
  Les notes enregistrées s’en souviennent toujours, peu importe combien de fois
  on les quantifie.
- **groove** - quantifie sur le timing d’un autre clip au lieu d’une grille
  droite, comme le contretemps paresseux d’un batteur. Le swing du morceau
  s’ajoute à la lecture, donc quantifiez sur une grille droite pour le swing.
- **humaniser** - chaque appui ajoute de petits changements au hasard au
  timing, au volume et à la longueur. Appuyez encore pour un autre résultat, ou
  sur **aucun** pour les retirer. Le bouton règle combien.

## Réglages par note

Sous la grille, il y a un couloir qu’on peut ouvrir. Choisissez ce qu’il
montre, puis glissez dedans pour régler cette valeur sur chaque note.

- **volume de la note** - la vélocité de la note.
- **probabilité** - à quelle fréquence elle joue. 50 veut dire la moitié du
  temps.
- **condition de déclenchement** - quand elle a le droit de jouer. `1:4` joue
  à la première de chaque série de quatre boucles. `pre` joue seulement si la
  dernière note conditionnelle a joué, et `!pr` seulement si elle n’a pas joué.
  `fil` joue seulement pendant qu’on maintient fill, et `!fi` seulement quand
  on ne le maintient pas.
- **ratchet** - combien de fois la note se répète dans son pas.
- **micro-décalage** - pousse la note hors de la grille, jusqu’à une
  demi-double-croche dans un sens ou dans l’autre.
- **paroles** - sur une piste Diction ou Tongue, ce que chaque note chante ou
  dit. Touchez une note pour les taper. Voir [Diction](04-the-machines/diction.md)
  et [Tongue](04-the-machines/tongue.md).

Le hasard utilise une graine fixe, donc la même variation revient chaque fois.

## Les boutons

Le panneau de la machine est entre la grille et le clavier. Ses commandes sont
en sections, et les onglets en haut montrent une section à la fois. Les
insertions, les modificateurs et les envois ouvrent le même genre de panneau
dans une fenêtre.

Maintenez un bouton pour le remettre où il était à l’ouverture du panneau. Les
faders et les curseurs font pareil, y compris dans le mixage.

Quand l’**affectation** MIDI est active, maintenir une commande efface plutôt
son affectation.

## L’automatisation

La bande sous le couloir par note enregistre et dessine un paramètre dans le
temps. Tournez un bouton pendant l’enregistrement et le mouvement s’y écrit,
ou choisissez un paramètre et dessinez-le à la main.

Un bouton qui a un couloir dans le clip ouvert a un **∿** sur son cadran, et le
couloir le fait bouger pendant que le clip joue.

Quand vous appuyez sur jouer, chaque bouton automatisé revient d’abord à sa
valeur dans le morceau, pour que le morceau commence pareil chaque fois, et un
export aussi. Dans le lanceur, ça ne se fait pas, et un clip lancé continue là
où le précédent a laissé les choses.

## Replier

Les couloirs, le panneau de la machine et le clavier se replient chacun avec
la petite flèche sur leur bord, pour donner plus de place à la grille.

## Sur un écran carré

Sur un téléphone à peu près aussi large que haut, la grille prend le haut de
l’écran et le clavier ou le panneau de la machine prend le bas, un à la fois.
**clavier** au début de la barre du bas (**pads** sur une boîte à rythmes)
passe de l’un à l’autre, et **fx** et le mixage font monter le panneau.

Les fenêtres tiennent dans l’écran là aussi, sans défilement. Les quelques-unes
qui n’y tiendraient pas sont coupées en pages. L’arpège en a trois
(**temps · toucher**, **motif**, **proba · marche**), et la tonalité de la
fenêtre du tempo et l’entrée de la fenêtre Son ont chacune leur propre onglet.
