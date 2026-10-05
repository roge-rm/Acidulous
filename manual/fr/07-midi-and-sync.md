# MIDI et jeu avec d’autres
Vous pouvez utiliser le MIDI avec toutes sortes de claviers et de contrôleurs, y compris MPE.
L’Intuitive Instruments Exquis et le Novation Launchpad Pro MK3 ont un support
particulier (je les ai moi-même).

## Jouer d’un clavier

Les claviers MIDI USB et Bluetooth fonctionnent tout de suite. Les notes vont à
la piste ouverte, ou à une piste que vous fixez pour qu’elle reste la même peu
importe ce que vous regardez.

Si un contrôleur joue tout trop fort ou trop doux, tournez **vélocité** dans
l’onglet **notes**. **plus doux** baisse le milieu de la plage et **plus fort**
le monte, et les notes les plus douces et les plus fortes ne bougent pas.
L’affichage à côté montre la vélocité de chaque note telle que l’appli la reçoit.

### MPE

Avec un contrôleur MPE, chaque doigt a son propre bend, sa pression et son
glissé, donc plier une note laisse les autres tranquilles. Laissez **zone** sur
**auto** et l’appli suit le contrôleur : elle prend la zone et la plage de bend
qu’il annonce, ou les devine dès que deux doigts sont posés sur des canaux
séparés. La carte dit ce qu’elle a trouvé. Choisissez **basse** ou **haute**
pour régler la zone et la plage de bend à la main.

Ce que fait chaque geste :

- **Bend** change la hauteur de la note sur toutes les machines mélodiques sauf
  Reflux, l’orgue et Diction, qui plient en bloc. Sur un harmonica Draw joué
  comme par un joueur, plier vers le bas plie avec la langue, aussi loin que le
  trou le permet.
- **Pression** ouvre le son et le rend plus fort. Sur Brazen, Timber, Draw et
  Tongue, c’est le souffle, et sur Filament elle appuie aussi sur l’archet.
  Hammer n’en tient pas compte : un piano n’a plus rien à presser une fois la
  corde frappée. **pression** sur le panneau d’une machine règle la quantité.
- **Glissé** (CC 74) rend la note plus brillante. Il ouvre le filtre sur
  Trinity, Ratio et Mosaic, monte le point de pincement sur la corde de
  Filament, serre les lèvres ou l’anche sur Brazen et Timber, rapproche les
  anches de Draw de leurs fentes, serre l’anche de Tongue dans son cadre et
  déplace le morph de Cumulus. **glissé** sur le panneau règle la quantité.
  Dans Nexus, le module **touch** donne à chaque voix la pression et le glissé
  de son doigt, à brancher où vous voulez.

Un clavier qui envoie de l’aftertouch polyphonique presse aussi chaque note
séparément, sans zone. Les autres contrôleurs sur le canal d’un doigt, comme la
molette de modulation ou la pédale de maintien, agissent sur toute la piste
comme d’habitude.

Le bend, la pression et le glissé de chaque note sont enregistrés avec les notes.

### Exquis

Branchez un Exquis en USB et ses pads montrent la gamme de la piste qu’il joue :
le modificateur Scale de la piste s’il en a un, sinon la tonalité du morceau,
comme la grille de notes. Elle change quand vous ouvrez une autre piste ou
changez la gamme. Avec **fixe**, c’est la piste fixée, et avec **par canal**,
c’est la piste du canal sur lequel joue l’Exquis. Les doigts MPE ne sont pas
dirigés par canal, donc en MPE c’est la piste ouverte ou la piste fixée.

**pads exquis** dans l’onglet appareils de la fenêtre MIDI choisit comment :

- **ses couleurs** règle la tonique et la gamme de l’Exquis lui-même, donc les
  pads les montrent dans les couleurs que vous leur avez données. Une gamme que
  l’Exquis n’a pas s’affiche comme la plus proche qui contient toutes ses notes,
  ou chromatique.
- **surbrillance** allume plutôt les notes dans le vert de surbrillance de
  l’Exquis, un pad par note, par-dessus la gamme réglée sur l’Exquis.
- **non** laisse les pads tranquilles.

Ses boutons commandent aussi l’appli : **play/stop** lance et arrête le morceau,
**record** arme l’enregistrement, **loop** met la scène en boucle, **clips**
passe en mode clip, et **undo** et **redo** font ce qu’ils disent. Leurs
lumières suivent l’appli : play est vert pendant la lecture, record est rouge
quand il est armé, et loop et clips sont allumés quand ils sont actifs. Les
pads, les boutons rotatifs, le curseur et les boutons d’octave restent à
l’Exquis. **boutons exquis** dans l’onglet appareils rend les boutons à
l’Exquis, et fermer l’appli aussi.

### Launchpad Pro

Branchez un Launchpad Pro [MK3] en USB et Acidulous prend en charge tous les
pads et boutons, allumés aux couleurs de vos pistes. **launchpad** dans l’onglet
appareils de la fenêtre MIDI le rend, et fermer l’appli aussi. Les boutons du
haut choisissent ce qu’est la grille :

- **Note** - un clavier de piano comme celui à l’écran, avec les touches
  blanches sur une rangée et les noires sur la rangée du dessus, quatre octaves
  vers le haut de la grille. Les notes de la gamme (celle de la piste, ou la
  tonalité du morceau) sont allumées de la couleur de la piste, la tonique plus
  brillante, et les autres sont pâles mais jouent quand même. Quand la piste a
  son propre modificateur **Scale** actif, seules les notes de la gamme sont
  là, une octave par rangée à partir de la tonique, ce qui donne huit octaves.
  Sur une boîte à rythmes, ce sont les pads de la machine, placés comme à
  l’écran. Haut et bas changent l’octave.
- **Session** - la grille du morceau, placée comme à l’écran avec les pistes
  vers le bas et les scènes de gauche à droite. Un clip pulse pendant qu’il
  joue et clignote pendant qu’il attend. Touchez un clip pour le lancer en mode
  clip, ou pour jouer à partir de cette scène en mode morceau.
- **Sequencer** - le clip de la piste ouverte dans la scène courante, huit pas
  à la fois. Touchez un pad pour ajouter ou enlever une note. Gauche et droite
  avancent d’un pas à la fois, et haut et bas parcourent les notes. Les rangées
  d’une boîte à rythmes descendent à partir de la grosse caisse, comme sa
  grille à l’écran.
- **Custom** - le mixage, placé comme la grille du morceau, avec une rangée par
  piste et son volume de gauche à droite. **Volume**, **Pan** et **Sends** sur
  la rangée du bas choisissent ce que sont les curseurs, et **Device** fait des
  rangées les huit premiers boutons de la machine jouée.
- **Chord** - des accords dans la tonalité, une colonne par note de la gamme et
  une rangée par sorte d’accord.
- **Projects** - les effets de performance : répétition et gate en haut,
  inversion, arrêt de bande, la montée, les trois coupures et un pad XY.
  Maintenez pour jouer.

Les boutons du pourtour fonctionnent sur toutes les pages :

- **Play** et **Record** font ce qu’ils disent. **Shift** et **Play** arrête
  tout.
- **Shift** et **Clear** annule, et **Shift** et **Duplicate** rétablit.
- Maintenez **Clear** et touchez un clip pour le vider. Maintenez **Duplicate**
  et touchez un clip pour le copier dans la scène suivante si elle est vide, ou
  touchez un bouton de scène pour dupliquer la scène.
- Les boutons à droite sont les pistes, de haut en bas comme les rangées de la
  grille. Touchez-en un pour choisir la piste que joue le Launchpad. Maintenez
  **Mute** ou **Solo** et touchez-en un pour la rendre muette ou la mettre en
  solo.
- La rangée sous la grille, ce sont les scènes, de gauche à droite. Touchez-en
  une pour la jouer, comme quand vous touchez l’en-tête d’une scène.
- Sur **Session** et **Custom**, haut et bas parcourent les pistes et gauche et
  droite les scènes, une à la fois. Sur les autres pages, maintenez **Shift**
  pour faire de même. Une flèche est allumée quand il y a plus de ce côté.
- **Quantise** quantifie le clip ouvert selon le dernier réglage de la fenêtre
  de quantification.
- **Stop Clip** arrête les clips, ou le morceau.

Ses pads envoient la vélocité et la pression, et les deux sont enregistrées
comme pour n’importe quel clavier.

### Pédales

Une pédale branchée à votre clavier fonctionne sur toutes les machines
mélodiques :

- **Maintien** (la pédale de droite) fait durer les notes après que vous
  lâchez les touches, jusqu’à ce que vous la releviez. Sur Filament, elle lève
  aussi les étouffoirs, donc les cordes que vous ne jouez pas résonnent avec.
- **Sostenuto** (celle du milieu) tient seulement les touches enfoncées au
  moment où vous l’avez pressée, et les notes jouées ensuite s’arrêtent comme
  d’habitude.
- **Douce** (celle de gauche) joue les notes plus doucement tant qu’elle est
  enfoncée.

Hammer prend une pédale enfoncée à moitié, comme un piano : une pédale de
maintien à mi-course laisse les étouffoirs juste toucher les cordes. Toutes les
autres machines entendent une pédale comme relevée ou enfoncée, et la mi-course
compte comme enfoncée. La sortie MIDI envoie la pédale aussi loin qu’elle est
enfoncée.

Les pédales sont enregistrées comme couloirs dans la bande d’automatisation, un
chacune, et vous pouvez aussi les y dessiner à la main. Sur Hammer, un couloir
garde jusqu’où la pédale était enfoncée et où elle a bougé; sur tout le reste,
c’est relevée ou enfoncée. Les boîtes à rythmes n’en tiennent pas compte.

## Affecter un contrôleur

Maintenez rétablir pour entrer en mode affectation. Les commandes qu’on peut
affecter sont mises en surbrillance. Touchez-en une, puis tournez un bouton ou
appuyez sur une touche de votre contrôleur pour les relier. Les boutons, les
curseurs, les commandes de mixage et les boutons de transport peuvent tous être
affectés à un CC ou à une note.

Un bouton affecté s’enregistre dans l’automatisation comme si vous le tourniez
à la main. Les boutons affectés comme jouer, arrêter et fill ne s’enregistrent
pas.

## Horloge

L’appli peut envoyer l’horloge MIDI (avec start, stop et song position) à du
matériel, et suivre une horloge entrante en prenant son tempo de l’autre
appareil.

**suivre** dans l’onglet **contrôle** de la fenêtre MIDI a trois réglages :

- **oui** suit toujours, même si rien n’arrive.
- **auto** suit une horloge quand elle arrive et revient au tempo du morceau
  une seconde après qu’elle s’arrête.
- **non** ignore l’horloge entrante.

**envoyer**, à côté, active et désactive l’envoi de l’horloge. L’envoi des notes
d’une piste se règle piste par piste, dans ses réglages ou sur sa tranche de
mixage.

## Link

Ableton Link partage le tempo et la position dans la mesure avec les autres
apps Link du même réseau, dans les deux sens. Activez-le et le nombre d’apps
connectées s’affiche. Il garde le premier temps de tout le monde ensemble sans
que personne soit le chef.
