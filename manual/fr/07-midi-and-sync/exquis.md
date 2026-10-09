# Exquis
> L’Exquis d’Intuitive Instruments comme contrôleur : son propre jeu, et le morceau, le mixage et les pas sur ses pads.

Branchez un Exquis en USB et Acidulous le joue comme contrôleur, tenu debout,
les boutons rotatifs en haut. Il faut le micrologiciel 2.1 de l’Exquis ou plus
récent. **exquis** dans l’onglet appareils de la fenêtre MIDI le rend, et fermer
l’appli aussi.

Ses boutons **settings** et **sound** restent à lui, donc ses menus sont là
comme d’habitude, par exemple pour activer ou couper le MPE.

Il y a quatre pages, et la couleur de **clips** montre laquelle : gris pour
Play, vert pour Session, orange pour Mixer et bleu pour Steps. Touchez **clips**
pour aller entre Play et la dernière page utilisée. Tenez-le et les pads du
milieu montrent les quatre pages dans leurs couleurs : touchez-en une pour y
aller.

Réglez **pages exquis** sur **notes seules** pour le garder sur Play, et jouer
en direct pendant que vous changez de scène et de clip à l’écran.

Sur toutes les pages :

- **record**, **loop** et **play** arment l’enregistrement, mettent la scène en
  boucle, et lancent et arrêtent le morceau. **undo** et **redo** font ce
  qu’ils disent. Leurs lumières suivent l’appli.
- Le **curseur** change le niveau de la piste jouée quand vous glissez dessus,
  et ses lumières montrent le niveau.
- Les **boutons rotatifs** sont ceux de la machine ouverte, quatre à la fois.
  Cliquez-en un pour les quatre suivants. Session et Mixer s’en servent
  autrement, voir plus bas.

## Play

![L’Exquis sur Play](../../images/exquis-play.png)

Les pads sont à l’Exquis, avec sa vélocité, sa pression et son MPE. Les flèches
montent ou descendent les notes d’une octave, et celle vers laquelle vous êtes
allé est allumée. C’est l’appli qui fait l’octave, pas l’Exquis, donc l’Exquis
reste à la sienne, et changer son octave dans ses propres menus décale tout.
Ses pads montrent la gamme de la piste
qu’il joue : le modificateur Scale de la piste s’il en a un, sinon la tonalité
du morceau, comme la grille de notes. Avec **fixe**, c’est la piste fixée, et
avec **par canal**, c’est la piste du canal sur lequel joue l’Exquis. Les doigts
MPE ne sont pas dirigés par canal, donc en MPE c’est la piste ouverte ou la
piste fixée.

**pads exquis** dans l’onglet appareils choisit comment la gamme s’affiche :

- **ses couleurs** règle la tonique et la gamme de l’Exquis lui-même, donc les
  pads les montrent dans les couleurs que vous leur avez données. Tant
  qu’Acidulous a l’Exquis, il reçoit la gamme exacte. Avec **exquis** sur
  **le sien**, une gamme que l’Exquis n’a pas s’affiche comme la plus proche
  qui contient toutes ses notes, ou chromatique.
- **surbrillance** allume plutôt les notes dans le vert de surbrillance de
  l’Exquis, un pad par note, par-dessus la gamme réglée sur l’Exquis.
- **non** laisse les pads tranquilles.

Sur une boîte à rythmes, le milieu des pads devient plutôt ses sons, allumés en
vert, la grosse caisse en bas et les autres dans l’ordre où l’appli montre ses
pads. La plupart des notes sont sur deux pads, donc la plupart des sons aussi,
et l’Exquis en allume un. Les autres pads ne jouent rien.

![L’Exquis sur une boîte à rythmes](../../images/exquis-drums.png)

## Session

![L’Exquis sur Session](../../images/exquis-session.png)

La grille du morceau, une rangée par piste avec la première en haut, et un pad
pour chacune des cinq scènes. Un clip pulse pendant qu’il joue et clignote
pendant qu’il attend. Touchez un clip pour le lancer en mode clip, ou pour
jouer à partir de cette scène en mode morceau.

La rangée du bas, ce sont les scènes : touchez-en une pour la jouer. Son
sixième pad arrête les clips, ou le morceau. Les flèches parcourent les pistes
cinq à la fois.

Quand il y a plus de scènes que de place, le bouton rotatif 1 est plus allumé et
le dernier pad d’une rangée sur deux brille faiblement tant qu’il en reste à
droite. Tournez le bouton 1 pour parcourir les scènes une à la fois, ou
cliquez-le pour les cinq suivantes, avec un retour au début après la dernière.
Le bouton 2 fait de même pour les pistes, dix à la fois.

## Mixer

![L’Exquis sur Mixer](../../images/exquis-mixer.png)

Une rangée par piste. Le premier pad choisit la piste que joue l’Exquis, et
brille sur celle-là. **M** coupe le son et **S** met en solo. Les pads suivants
sont son niveau : touchez-en un pour régler le niveau là, et touchez de
nouveau le plus haut allumé pour le baisser d’un cran. Les boutons rotatifs sont
les niveaux des quatre pistes du haut, et les flèches parcourent les pistes
cinq à la fois.

## Steps

![L’Exquis sur Steps](../../images/exquis-steps.png)

Le clip de la piste ouverte dans la scène en cours, seize pas à la fois sur les
trois rangées du haut. En dessous, les notes : les sons d’une boîte à rythmes,
ou la gamme à partir de l’octave. Touchez-en une pour choisir la note que les
pas écrivent, et elle joue pour que vous l’entendiez.

Un pas allumé de la couleur de la piste a cette note. Touchez un pas pour
l’ajouter ou l’enlever, et le pas qui joue est blanc. La rangée du bas, ce sont
les mesures du clip, seize pas chacune : touchez-en une pour la voir. Les
flèches changent l’octave des notes, sauf sur une boîte à rythmes.

Hors de la page Play, les pads disent seulement s’ils sont enfoncés ou non,
donc les pas sont écrits à une seule vélocité.
