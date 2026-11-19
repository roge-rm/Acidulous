# Abacus

> Un synthé additif : chaque note est une rangée d’au plus 64 partiels
> sinusoïdaux, chacun avec son propre niveau.

Abacus construit une note avec des sinusoïdes. Chacune est un partiel, et vous
réglez le niveau de chacun avec une forme : une pente, un pic, une voyelle, un
peigne. Les boules de la page montrent les partiels tels qu’ils sont, une tige
chacun, plus haut quand c’est plus fort. L’image est faite pour un do central
et ne suit ni l’enveloppe ni le scintillement.

## Spectre

- **spectre** : **pente** est la vitesse à laquelle les partiels
  s’affaiblissent, en dB par octave. -6 donne une dent de scie, 0 est brillant
  et bourdonnant, -24 est presque une sinusoïde. **impair/pair** va des seuls
  partiels pairs, en passant par tous, aux seuls partiels impairs (un carré).
  **partiels** : 16, 32 ou 64. Moins, c’est plus léger pour le processeur.
- **crête** : une bosse autour du **centre**, de **gain** dB, de **largeur**
  en octaves.
- **voyelle** : **a e i o u** choisit la voyelle et **intensité** la dose. Elle
  met deux pics là où la voix les a.
- **peigne** : retire un partiel sur n, de **prof.** 1 sur un partiel
  sur 3 donne une impulsion au tiers.
- **bout lointain** : un deuxième spectre. Ses boutons sont des écarts par
  rapport au premier. **morph** va de l’un à l’autre.

## Partiels

- **loi** : où se placent les partiels : **harmonique**, une **corde**
  raide, une **barre** libre, une **cloche** ou une **plaque** carrée. Les
  trois dernières ne sont pas des multiples entiers de la note : elles
  sonnent comme des objets frappés.
- **étirer** : écarte la loi d’elle-même. En harmonique et en corde, c’est
  un léger étirement, comme celui d’un piano. En barre, cloche et plaque, -1
  donne l’harmonique et 1 deux fois plus loin que la loi.
- **amorti** : **amortir** et **déclin**. Chaque partiel s’éteint. **déclin**
  est le temps du premier partiel, 30 s tient. **amortir** fait mourir les
  aigus plus vite.
- **écart** : **largeur** place les partiels dans le champ stéréo. **mode** :
  **impair/pair** (les impairs d’un côté, les pairs de l’autre), **épars**
  ou **grave-aigu**. À 0, c’est mono.
- **miroitement** : chaque partiel dérive seul en niveau et en hauteur, de
  **intensité** et de **vitesse**.

## Mouvement

- **env. morph** : **prof.** et **temps** : une note part du bout
  lointain et revient vers le proche (négatif, dans l’autre sens). Un temps
  court donne une attaque brillante.
- **lfo 1** : **onde**, **vitesse**, **horloge** (libre ou calée sur le
  morceau), puis de combien il bouge la hauteur (**→ hauteur**) et le morph
  (**→ morph**).
- **lfo 2** : pareil, pour l’éclat (**→ brill.**, la pente) et le pano
  (**→ pano**).
- **voix** : **brill. vél.** (de combien une touche plus dure ouvre la
  pente), **porta.**, **bend**, **glissé** et **pression** (ce que fait un
  doigt MPE).
- **accordage** : **octave**, **demi-tons** et **précis**.

## Comptage

Les partiels peuvent jouer en rythme, sur l’horloge du morceau : la mesure 5
sonne pareil qu’on parte de la mesure 1 ou de la mesure 5. **comptage** est
le motif : **off**, **up**, **down**, **up-down**, **impair/pair** (la moitié
grave et la moitié aiguë tour à tour), **quintes** (chaque pas une quinte plus
haut, replié sur deux octaves) ou **aléatoire** (nouveau à chaque mesure, le
même à chaque lecture).

- **motif** : **comptage**, **vitesse** (de 1/32T à une mesure ; elle suit le
  swing de la piste) et **pas**, le nombre de pas avant que le motif reparte.
- **allumés** : **de** est le plus bas partiel que joue le motif. Ceux
  du dessous sonnent toujours, donc la note garde sa hauteur. **largeur** est
  le nombre de partiels allumés à chaque pas. **prof.** est de combien les
  autres baissent, 0 ne change rien et 1 les rend muets. **porta.** est la
  part du pas passée à fondre depuis les partiels du pas précédent.

Morceau arrêté, il continue de compter au tempo. **prof. du compte** et
**porta. du compte** peuvent être des destinations de macro.

## Glissé

Chaque partiel peut rejoindre sa hauteur à son propre rythme. Jouez une note
pendant qu’une autre est tenue, ou relâchez-en une, et les partiels de la
nouvelle note partent de la hauteur de l’ancienne et arrivent l’un après
l’autre : ils se mettent en place en roulant, au lieu de glisser d’un bloc.

- **glissé** - **durée** est le temps du plus lent, jusqu’à 4 s ; le bas du
  bouton est à zéro. **écart** est la distance entre les temps : à 0 tous
  prennent toute la durée, à 1 le premier en prend un dixième. **ordre** dit
  qui arrive d’abord : **graves d’abord**, **aigus d’abord**, **épars** (un
  nouvel ordre à chaque note, le même à chaque lecture) ou **du centre**.
  **quand** est **toujours** ou **legato seul**, qui ne glisse que si la
  touche précédente est encore enfoncée, ou vient d’être relâchée.
- **gratter** - au début d’une note les partiels entrent un par un, dans l’
  **ordre** ci-dessus, pendant ce temps. 0 : sans effet.

La première note après un silence n’a rien d’où glisser. Le **porta.** est
laissé de côté sur une note qui glisse. Les bends par note, le comptage et
l’emprunt fonctionnent pendant le glissé. **durée du glissé**, **écart du
glissé** et **gratter** peuvent être des destinations de macro. La durée et l’ordre sont pris au début de la note.

## Emprunt

Les partiels peuvent prendre leurs niveaux à un autre son pendant qu’il joue :
une autre piste, ou un fichier. Vos notes gardent leur hauteur et l’autre son
leur donne sa couleur et son volume, donc une nappe peut parler avec une voix
ou respirer avec une piste de batterie.

- **emprunt** : **de** vaut **non**, **piste** ou **fichier**. **lecture** est
  la façon de lire le son. **enveloppe** prend sa couleur d’ensemble, ses
  formants et sa brillance, et la pose sur vos partiels par fréquence.
  **partiels** suit sa hauteur et prend ses harmoniques par numéro, son 3e sur
  votre 3e, ce qui demande un son à une seule hauteur claire. **intensité**
  passe des niveaux propres des partiels aux niveaux empruntés.
- **suivre** : **lisse** est la vitesse à laquelle les niveaux empruntés
  suivent, court pour la parole, plus long pour une nappe plus douce. **figer**
  sur **tenir** garde ce qu’il a et cesse d’écouter.
- **piste** : la piste à laquelle emprunter. Sans piste choisie, les partiels
  jouent leurs propres niveaux.
- **fichier** : **importer…** ouvre un fichier, **échantillons…** en choisit un
  que vous avez, et **voix** est une ligne chantée fournie avec l’app.
  **position** est l’endroit du fichier qu’il lit, et **balayage** le parcourt,
  1 à la vitesse du fichier, négatif à l’envers, en boucle aux extrémités. Un
  fichier est lu jusqu’à une minute.

Le grondement sous 70 Hz est laissé de côté, pour que le ronflement d’une piste
ne colore pas les partiels. Quand l’autre piste se tait, Abacus aussi.
**intensité emprunt**, **lissage emprunt**, **position fichier** et **balayage
fichier** peuvent être des destinations de macro.

## Jeu

La page **jeu** a les huit boutons macro, l’enveloppe d’amplitude (**A**,
**D**, **S**, **R**), la **vélocité**, le **volume** et le **pano**. La
pression ouvre la pente et relève le niveau. Suit l’accord du morceau, et en
MPE chaque doigt plie et éclaircit sa propre note.

## Macros

Les patchs nomment leurs macros d’après ce qu’elles font : Bright, Hollow,
Morph, Fade, Shimmer, Wide, Slow et Comb sur les patchs classiques, de cordes
et d’orgue (l’orgue a Click et Vibrato à la place de Fade et Slow), Vowel et
Voice sur les voix, Strike, Ring, Damp et Bar sur les lames, Stretch et Swell
sur les cloches, Depth et Glide sur les patchs count, Amount, Smooth, Place et
Scan sur les patchs borrow, et Slide, Spread et Strum sur les patchs slide. Un
lead a Glide pour sa hauteur et une basse a Release. Elles sont à zéro au
chargement d’un patch, et quand l’une change le niveau, elle tourne aussi le
volume pour compenser.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1). Tourner le bouton
déplace la destination de bouton fois quantité, sur toute l’étendue du
réglage à 1. La hauteur déplace toutes les notes, de 24 demi-tons à 1. Les
partiels, l’espacement du peigne, la loi, le mode d’étalement, les formes et
horloges des LFO, le bend, l’octave et les demi-tons, et le motif, la vitesse, les pas, le
départ et la largeur du comptage, ne sont pas accessibles. Touchez le nom d’une macro pour la renommer.

## Conseils

- Un patch qui tient n’a pas de chute, donc **amorti** ne fait rien tant que
  vous ne baissez pas **chute**. La macro Fade fait les deux.
- Pour un son frappé, montez **enveloppe morph** avec un **temps** court et
  donnez au **bout lointain** une pente plus brillante.
- Les patchs forment neuf familles : classic, organ, vocal, strings, mallets,
  bells, count, borrow et slide.
- Les patchs borrow qui lisent une piste n’ont pas de piste choisie : choisissez
  en une sur la page **emprunt**, une boucle de batterie ou une voix marche bien.
