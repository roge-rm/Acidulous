# Divisi

> Un ensemble de cordes : des violons à la contrebasse, un chœur et des
> cuivres, le tout dans un chorus épais et un phaser.

Divisi se joue comme les vieilles machines à cordes. Douze oscillateurs, un
pour chaque nom de note, sont divisés par octaves : tous les do du clavier
viennent du même oscillateur et restent en phase. Chaque touche tenue envoie
sa note dans six registres, et chaque registre a son propre niveau.

Par défaut, une seule enveloppe fait monter tout le son. Une touche jouée
pendant que d’autres sont tenues rejoint le son tel qu’il est, et la relâche
ne commence qu’une fois toutes les touches levées. Mettez **mode** sur
**poly** pour donner à chaque note sa propre attaque et sa propre relâche.

## Registres

- **violon** : brillant, à la note.
- **alto** : plus sombre, à la note.
- **violoncelle** : une octave plus bas.
- **contrebasse** : deux octaves plus bas.
- **ton** : plus sombre ou plus brillant, sur les quatre.

## Chœur

- **niveau** : le niveau du chœur.
- **voyelle** : de **u** à **i** en passant par **o**, **a** et **e**, du
  plus sombre au plus brillant.

## Cuivres

Une dent de scie par touche, dans un filtre qui s’ouvre avec la note, dépasse
puis retombe pendant que la note est tenue.

- **niveau** : le niveau des cuivres.
- **balayage** : jusqu’où le filtre s’ouvre.
- **déclin** : le temps que met une note tenue à retomber.
- **éclat** : le mordant au début : jusqu’où le filtre dépasse, avec quel
  retard il s’ouvre et de combien la hauteur glisse vers la note.

En mode paraphonique, les touches partagent un seul filtre de cuivres, ouvert
par la première touche : jouez legato. En mode poly, chaque touche a le sien.

## Ensemble

- **mode** : **para.** (une enveloppe pour toutes les touches) ou **poly**
  (une chacune).
- **attaque** et **relâche** : jusqu’à 5 et 10 secondes.
- **prof.** et **vitesse** de l’**ensemble** : le chorus, deux lignes à
  retard de chaque côté, chacune bougée par une ondulation lente et une
  rapide, le côté droit à l’inverse du gauche. À 0, on entend les
  oscillateurs tels quels.
- **prof.**, **vitesse** et **réinjection** du **phaser**.

## Joueurs

Chaque registre est un pupitre de joueurs. Plus il y a de joueurs, plus le
son est épais, chacun un peu décalé des autres.

- **joueurs** : combien par pupitre, de 1 à 16. Un seul joueur sans dérive ni
  vibrato donne le son divisé tel quel.
- **dérive** : jusqu’où la hauteur de chaque joueur se promène, lentement, en
  cents.
- **vibrato** et **vitesse** : le vibrato de chaque joueur, sa profondeur en
  cents. Celui de chaque joueur est un peu plus rapide ou plus lent que celui
  des autres.
- **épars** : le retard possible d’un joueur à l’entrée, jusqu’à 80 ms.
  L’ordre change chaque fois qu’on joue après un silence.

Les joueurs sont répartis sur la largeur de chaque pupitre. Plus il y a de
dérive, plus le son arrive tard : jusqu’à environ 20 ms au maximum.

## Scène

Chaque pupitre a sa place sur une scène : les violons devant à gauche, les
altos juste à droite du centre, les violoncelles à droite, les contrebasses
derrière eux, les cuivres au fond à gauche du centre et le chœur au fond. Un
pupitre plus loin arrive plus tard, moins fort et plus sourd, et chaque
pupitre a son propre chorus.

- **écart** : jusqu’où les pupitres s’étalent sur la scène. À 0, ils sont
  tous au centre et partagent un seul chorus. Au maximum, le chœur arrive
  environ 30 ms après les violons.
- **salle** : le son de la scène elle-même : les réflexions sur les murs et
  une ambiance courte et sombre de moins d’une seconde. On entend les rangs
  du fond surtout par la salle, ceux de devant surtout en direct. Ce n’est
  pas une grande salle : mettez une réverbération après pour ça.
- **partage** : chaque pupitre garde sa partie du clavier : les contrebasses
  en bas, puis les violoncelles, les altos et les violons en haut, le chœur
  et les cuivres au milieu. Un accord s’ouvre sur la scène et une ligne qui
  monte passe des violoncelles aux violons. Chaque note est réglée quand on
  la joue. Un pupitre éteint laisse sa partie au suivant.

## Mouvement

Le mouvement fait bouger les pupitres lentement pendant que vous tenez un
accord, comme s’ils s’écoutaient.

- **forme** : ce qui bouge :
  - **dérive** : chaque pupitre erre de son côté, plus fort et plus doux,
    plus clair et plus sombre.
  - **crescendo** : tout l’ensemble respire en même temps, plus doux et plus
    sombre au creux de chaque souffle. Un accord joué dans le silence part du
    creux.
  - **marée** : une vague traverse la scène, des violons devant à gauche aux
    contrebasses au fond à droite.
  - **pas** : une nouvelle balance à chaque pas, comme un chef qui fait
    entrer et sortir les pupitres. Chaque changement glisse au début de son
    pas.
- **vitesse** : de un pas ou une vague en 50 s à deux par seconde.
- **Synchro** : sur **libre**, elle suit la vitesse. Sinon un pas ou une vague
  dure une valeur de note ou un nombre de mesures. Pendant que le morceau
  joue, elle suit sa position, donc une mesure sonne pareil à chaque fois.
- **balance** : jusqu’où le niveau de chaque pupitre bouge, jusqu’à environ
  6 dB dans un sens ou l’autre, les pupitres ensemble gardant leur niveau. En
  crescendo, c’est le souffle, jusqu’à 12 dB plus bas.
- **voyelle** : jusqu’où la voyelle du chœur bouge.
- **ton** : jusqu’où chaque pupitre devient plus clair ou plus sombre,
  jusqu’à une octave dans un sens ou l’autre.
- **ensemble** : jusqu’où le chorus de chaque pupitre devient plus ou moins
  profond.

La dérive et la marée s’entendent le mieux avec l’**écart** monté, pour que
les pupitres soient séparés.

## Notes

- **accordage** (en cents), **octave**, **bend**, **vélocité** et **volume**.

Suit l’accordage du morceau, et la pression fait gonfler chaque note. En MPE,
chaque doigt infléchit et fait gonfler sa propre note.

## Macros

La page **jeu** a huit boutons macro. Les patchs les nomment Bright, Swell,
Longer, Lush, Voices, Brass, Phase et Low, ou l’inverse quand un patch en a
déjà beaucoup (Dark, Sharper, Shorter, Drier, Vowel, Blat, Still, Light). Ils
sont à zéro au chargement d’un patch, ce qui donne le patch tel qu’il a été
fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace toutes les notes, 24
demi-tons à 1. Le mode, l’accordage, l’octave, le bend et la forme et la
synchro du mouvement ne sont pas accessibles. Touchez le nom d’une macro pour
la renommer.

## Astuces

- Un gros accord avec tous les registres à fond et à pleine vélocité devient
  fort. Baissez un peu la vélocité ou le volume quand vous empilez tout.
- **Para Brass** et **Brass Swell** partagent un seul filtre : tenez un accord
  et ajoutez-y des notes plutôt que de rejouer chaque note.
