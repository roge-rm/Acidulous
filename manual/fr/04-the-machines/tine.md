# Tine

> Lames, languettes et tambours d’acier modélisés : marimba, vibraphone,
> xylophone, glockenspiel, piano à pouces, boîte à musique, steelpan,
> handpan, tambour à languettes, cloches tubulaires, crotales, gamelan, gong,
> bol chantant, tambour à fente, temple blocks, cloche à vache et triangle.

Tine ne joue pas d’enregistrements. Chaque note est une poignée de
résonances accordées comme l’objet frappé. Une lame de marimba est creusée
en dessous pour que sa première harmonique sonne deux octaves plus haut et
la suivante deux octaves et une tierce; celle d’un xylophone est creusée à
la douzième; la lame d’acier d’un glockenspiel est laissée telle quelle,
ses harmoniques loin de tout accord. La languette d’un piano à pouces est
tenue par un bout, ce qui place ses harmoniques plus haut encore, et la note
d’un steelpan est martelée jusqu’à sonner son octave et sa douzième.

Une mailloche douce reste quelques millisecondes sur la lame et ne peut que
lancer la note; une dure est repartie avant que les harmoniques démarrent, et
elles sonnent.

## Barre

- **modèle** : **marimba**, **vibra**, **xylophone**, **glock**, **pouce**
  (piano à pouces), **boîte à musique**, **steelpan**, **handpan** ou
  **tambour à lang.** (tambour à languettes), **cloches** (tubulaires),
  **crotales**, **saron** et **bonang** (gamelan), **gong**, **bol** (bol
  chantant), **tambour à fente**, **temple block**, **cloche** (à vache) ou
  **triangle**. Un balafon, c’est le marimba avec **buzz** monté.
- **accordage** : en cents.
- **paire** : chaque note sonne deux fois, la seconde plus haute de ce
  nombre de cents, et les deux battent comme les paires d’instruments d’un
  gamelan. Ça donne aussi un vibraphone ou des cloches en chorus. À 0, une
  note sonne une fois. La monter depuis 0 s’entend à la note suivante; sur
  une note déjà en paire, elle bouge tout de suite.
- **mailloche** : la dureté de la mailloche, du doigt ou de la goupille :
  de la laine douce au métal nu. Jouer plus fort la durcit un peu aussi.
- **position** : où elle est frappée. Au milieu, la deuxième harmonique
  d’une lame reste discrète; vers le bout, la note s’amincit et les
  harmoniques ressortent.
- **déclin** : combien de temps les notes sonnent. Les graves sonnent
  toujours plus longtemps.
- **brillant** : la part des harmoniques, et leur durée.
- **amortir** : la barre d’étouffoirs : combien une note s’arrête quand vous
  la lâchez. La pédale de sustain la lève.

## Corps

- **tube** : le tube sous chaque lame, qui sonne avec la note (et ses
  harmoniques impaires). Sur les steelpans et le tambour à languettes, c’est
  la coque.
- **moteur** : les disques qui tournent dans les tubes d’un vibraphone, en
  tours par seconde. À 0, ils sont à l’arrêt.
- **prof.** : combien les disques font pulser la note. La molette de
  modulation s’y ajoute.
- **éclosion** : sur les steelpans, combien l’octave naît de la note après
  la frappe.
- **buzz** : une ferraille qui touche la languette quand elle bat large.

## Mains

- **roulés** : les notes tenues frappées encore et encore, tant de fois par
  seconde, jamais tout à fait régulièrement. À 0, une note est frappée une
  fois.
- **écart** : les graves à gauche, les aigus à droite.
- **voix**, **vélocité**, **octave**, **bend** et **volume**.

La pression (du clavier, ou de chaque note avec le MPE) passe un archet sur
une lame tenue : la note enfle et tient tant que vous appuyez.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Mailloche,
Miroitement, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace tout l’instrument, 24
demi-tons à 1. Le modèle, l’accordage et les choix par pas ne sont pas
accessibles. Un changement de mailloche, de position, de déclin, de brillant,
d’écart ou de vélocité s’entend à la note suivante, ou au coup suivant d’un
roulé; amortir à la prochaine note lâchée. Le tube, le moteur, la prof.,
l’éclosion, le buzz, les roulés, le volume et la paire d’une note en paire
bougent tout de suite. Touchez le nom d’une macro pour la renommer.

## Astuces

- **Vibraphone** arrête chaque note quand vous la lâchez; tenez la pédale
  de sustain pour les laisser sonner, comme un vibraphoniste.
- **Vibes Motor** et **Slow Motor** font tourner les disques; la molette
  creuse la pulsation.
- **Marimba Roll**, **Vibes Roll** et **Pan Roll** demandent des notes
  tenues.
