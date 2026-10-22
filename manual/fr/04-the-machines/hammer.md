# Hammer

> Des pianos modélisés et leurs cousins : des marteaux qui frappent des cordes
> et des barres, entendus par une table d’harmonie ou des capteurs.

Hammer ne joue pas d’enregistrements. Un marteau de feutre est lancé sur de
vraies cordes qui résonnent par une table d’harmonie, et il se joue donc comme
un piano : plus fort, c’est plus brillant en plus d’être plus fort, les notes
graves résonnent longtemps, et les cordes d’une même touche battent entre elles
en s’éteignant.

Chaque touche a été mesurée sur un enregistrement de piano de concert, et
**Init** est ce piano.

## L’instrument

**modèle** choisit l’instrument :

- **à queue** - un piano de concert, tel que mesuré.
- **droit** - des cordes plus courtes et une table plus petite : moins de
  basses, plus de médium, et des notes qui meurent plus tôt.
- **honky-tonk** - un vieux piano droit avec les cordes de chaque touche
  désaccordées entre elles.
- **fortepiano** - un piano des environs de 1800 : de légers marteaux de cuir,
  des cordes fines, un son vif et clair avec un petit choc dedans.
- **à queue élec.** - des cordes courtes entendues par des capteurs plutôt que
  par une table.
- **tige** - un marteau frappe une fine tige de métal à côté d’une barre
  accordée, et un capteur l’entend : une cloche par-dessus, une longue note
  chaude, un grognement quand vous appuyez fort.
- **anche** - une anche d’acier et un capteur qui l’entend plus d’un côté que
  de l’autre : plus nasillard, plus vite éteint, un grondement joué fort.
- **tangente** - une tangente de métal frappe une corde et y reste tant que la
  touche est enfoncée. Relâchez et la note s’arrête net. Deux capteurs sous les
  cordes.
- **célesta** - des marteaux de feutre sur des barres d’acier, chacune au-dessus
  d’une petite boîte de bois : une cloche douce, avec des étouffoirs et une
  pédale.
- **jouet** - de petits marteaux durs sur des tiges de métal dans une boîte de
  plastique : métallique, court, jamais tout à fait juste (la graine choisit
  comment), sans étouffoirs.
- **tympanon** - de légers marteaux de bois sur des chœurs de cordes fines,
  sans aucun étouffoir, et tout résonne dans tout.
- **cymbalum** - un gros tympanon : des cordes plus lourdes, des marteaux
  doux, et une pédale d’étouffoirs.

**taille** va du petit piano à queue au piano de concert : un plus petit a des
basses plus minces et plus raides. **âge** use le piano. Les marteaux durcissent,
les cordes résonnent moins longtemps et plus sombre, et les touches se
désaccordent. **graine** choisit dans quel sens dérive chaque touche.

## Le marteau

- **dureté** - un feutre plus doux est plus sombre et plus rond, un plus dur
  est plus brillant. **par touche** rend le haut plus dur et le bas plus doux,
  ou l’inverse.
- **poids** - un marteau plus lourd reste plus longtemps sur les cordes, ce qui
  est plus sombre.
- **frappe à** - l’endroit de la corde où tombe le marteau. Monté, il tombe
  plus loin du bout, ce qui est plus rond. Baissé, c’est plus mince et plus
  brillant.
- **vélocité** - à quel point la force de votre jeu change le volume d’une
  note. Le coup suit toujours votre jeu : une note douce reste plus sombre.
- **punaises** - des punaises de métal enfoncées dans le feutre : chaque note
  dure et brillante, si doucement que vous jouiez. Un piano à punaises.
- **modérateur** - une bande de feutre entre les marteaux et les cordes : doux
  et étouffé.
- **roulement** - refrappe une note tenue encore et encore, ce nombre de fois
  par seconde, jamais tout à fait régulièrement : le trémolo d’un dulcimer ou
  d’un cymbalum, ou un trémolo de mandoline sur le piano à queue. À 0, une note
  est frappée une fois.

## Les cordes

- **maintien** - combien de temps les cordes résonnent. **par touche** fait
  résonner le haut plus longtemps et le bas moins, ou l’inverse.
- **ton** - à quelle vitesse le haut du son meurt par rapport au reste. Baissé,
  c’est un piano qui s’assombrit en résonnant, monté le garde brillant.
  **par touche** l’incline sur le clavier, comme pour le maintien.
- **raideur** - la raideur des cordes. Des cordes raides placent leurs
  partiels aigus un peu trop haut, et c’est surtout ce qui fait qu’un piano
  sonne comme un piano. **étirer** est à quel point l’accordage suit cela,
  comme un accordeur étire les octaves.
- **cordes** - combien de cordes a chaque touche. **auto** est celui d’un piano
  à queue : une dans les notes les plus graves, puis deux, puis trois.
- **unisson** - à quel point les cordes d’une touche sont désaccordées entre
  elles. Un peu donne la longue résonance lente, plus donne un honky-tonk.
- **couplage** - à quel point les cordes se parlent par le chevalet.
- **travers** - à quel point les cordes bougent en travers de la table en plus
  de vers elle, ce qui garde une note sonnante après la première chute rapide.
- **duplex** - sur les pianos, les petites longueurs de corde au-delà du
  point de frappe et au-delà du chevalet sont laissées libres, accordées près
  des harmoniques de la note. Elles résonnent faiblement avec elle, surtout
  dans l’aigu, ce qui ajoute un miroitement. À 0, elles se taisent.

## Les pédales

**étouffoirs** est la fermeté avec laquelle le feutre arrête une note quand
vous lâchez la touche, et **temps** la durée que ça prend. Les touches du haut
n’ont pas d’étouffoirs, comme sur un piano, et continuent de sonner.

La pédale de maintien lève les étouffoirs, et Hammer la suit en partie :
une demi-pédale les laisse toucher les cordes, ce qui enlève le haut d’une note
et laisse sonner le reste. **lève à** est la profondeur où les étouffoirs
commencent à quitter les cordes, et **étendue** combien plus loin ils en sont
dégagés.

Pédale enfoncée, les cordes que vous n’avez pas jouées résonnent avec celles
que vous avez jouées, à une octave ou une quinte. **sympathie** en règle la
quantité. **bruits** est le choc des étouffoirs qui quittent les cordes et y
retombent.

La pédale douce est **una corda** : le marteau glisse pour manquer une corde et
frappe les autres avec un feutre plus doux, et la note est plus faible et plus
sombre. Le bouton règle de combien. Sur un piano droit, les marteaux se
rapprochent plutôt des cordes, et frappent plus doucement.

## Le son

**table** est le volume du choc de la table d’harmonie sous chaque note.
**capot** ouvert est brillant, fermé enlève le haut. **salle** est la part de
la pièce que vous entendez.

**mic** est l’endroit d’où vous écoutez :

- **joueur** - au clavier, les basses à gauche.
- **public** - devant le piano, à l’inverse et plus étroit.
- **proche** - dans le piano, large et brillant.
- **salle** - de l’autre bout de la pièce.

**largeur** est la largeur de l’étalement du clavier.

## Préparer

Des objets posés sur les cordes, comme sur un piano préparé.

- **quoi** - du **caoutchouc** coincé entre les cordes étouffe presque toute la
  note et laisse une résonance terne. Une **vis** désaccorde les partiels,
  comme une cloche. Un **boulon** cliquette. Le **papier** grésille.
  **mélangé** met un objet différent sur chaque touche.
- **touches** - quelles touches : toutes, les blanches ou les noires, la moitié
  grave ou aiguë, ou certaines de chaque au hasard (la graine choisit).
- **où** - à quelle distance le long de la corde. Près du bout, ça change
  moins.
- **intensité** - à quel point c’est lourd, lâche, présent.

## L’électrique

Pour les modèles tige, anche et tangente.

- **proche** - la distance du capteur. Plus près, ça grogne plus tôt.
- **décalage** - à quel point la tige est décentrée par rapport au capteur.
  Près du centre, elle sonne une octave plus haut et creux. Plus loin, c’est la
  note, plus pure.
- **barre ton.** - la part de la note d’une tige que retient sa barre de ton :
  plus, c’est plus long et plus doux, moins, c’est plus de cloche.
- **capteurs** - quels capteurs de la tangente vous entendez : celui du
  **chevalet** (brillant), celui du **manche** (plus plein), **les deux**, ou
  les deux **opposés** l’un à l’autre (mince et creux). **sourdine** est une
  bande de feutre sur les cordes : court et pincé.
- **satur.** - l’ampli, du net au rugueux.
- **prof.**, **vitesse** et **pano** forment le trémolo. **sync** le cale sur
  le tempo. Avec **pano** au maximum, il balance d’un côté à l’autre plutôt que
  de haut en bas. La molette de modulation fait entrer le trémolo.

## La sortie

**volume** et **pano**, et l’accordage : **bend**, **octave**, **transposer**
et **précis**.

**voix** est le nombre maximal de notes qui sonnent à la fois. **détail** sur
**auto** suit le réglage de qualité, et un téléphone plus lent joue un piano
plus léger. **complet** joue toujours le modèle entier.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Couvercle, Feutre,
etc.) et les noms s’affichent sur les boutons, dans les lanes d’automation et
dans la liste de mappage du contrôleur. Ils sont à zéro au chargement d’un
patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace tout l’instrument, 24
demi-tons à 1. Le modèle, les interrupteurs et les choix par pas, et les pas d’accord ne sont pas accessibles. Presque tout un piano se règle quand une touche est frappée, alors un changement du marteau, des cordes ou des
étouffoirs, et la **largeur** de la table, s’entend à la note suivante ; le reste de la table, la salle, l’ampli, le trémolo, **duplex**, **roulement**, le
volume et le pano bougent tout de suite. Touchez le nom d’une
macro pour la renommer.

## Astuces

- La couleur d’un piano vient surtout du marteau. Essayez **dureté** avant
  tout le reste.
- Pour un piano plus vieux, montez **âge**, ou baissez **ton** et montez un peu
  **unisson**.
- Tenez la pédale de maintien et jouez dans le grave : les cordes résonnent les
  unes dans les autres.
- Pour un piano à tiges qui grogne, montez **proche** et jouez fort.
