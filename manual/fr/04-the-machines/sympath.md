# Sympath

> Sitar, tanpura, veena, shamisen et leurs parents modélisés, pincés ou
> frottés : des cordes sur un chevalet qui grésille, et des cordes qui vibrent
> par sympathie.

Sympath ne joue pas d’enregistrements. Chaque note est une corde posée sur
un chevalet large et bombé. Quand la corde oscille assez loin vers le
chevalet, elle le frappe, d’autant plus fort qu’elle va loin, et chaque choc
fait sonner le chevalet : ce grésillement brillant, à chaque oscillation,
qui dure tant que la corde va assez loin pour l’atteindre et s’éteint avec
la note.

Sous les cordes jouées se trouvent des cordes sympathiques qu’on ne touche
jamais. Elles sont accordées sur la gamme du morceau : toute note qui tombe
sur l’une d’elles la fait sonner, et elle sonne encore après la note.

Le reste du morceau peut aussi les faire sonner. Choisissez une autre piste
et son son secoue les cordes sympathiques et de bourdon : elles sonnent sous
tout ce que joue cette piste, même sans rien jouer sur Sympath. Montez
**écouter** et elles se réaccordent d’elles-mêmes sur les notes de cette
piste.

## Cordes

- **modèle** : **sitar** (onze cordes sympathiques et deux cordes de
  bourdon), **tanpura** (quatre cordes qu’il pince tout seul), **veena** (un
  chevalet plus plat et trois cordes de bourdon sur le côté) ou
  **shamisen** (un grand plectre qui frappe aussi la peau, et sa corde grave
  qui grésille contre le manche). Pincés aussi : **sarod** (sans frettes, une
  plaque d’acier sous les cordes), **rudra veena** (grave et lente),
  **swarmandal** (une cithare), **biwa**, **sanxian**, **ektara** (une corde)
  et **e-sitar** (un sitar électrique). Frottés à l’archet tant que la touche
  est tenue : **sarangi**, **esraj**, **dilruba**, violon **hardanger** et
  **nyckelharpa**.
- **accordage** : en cents.
- **sa** : la tonique sur laquelle sont accordées les cordes sympathiques et
  de bourdon.
- **gamme** : la gamme des cordes sympathiques : **bilawal** (majeur),
  **khamaj**, **kafi**, **asavari**, **bhairavi**, **bhairav**, **kalyan**,
  **marwa**, **purvi** ou **todi**.
- **chevalet** : combien le chevalet grésille. À 0, c’est une arête nette.
- **courbe** : jusqu’où la corde doit osciller avant de se coucher sur le
  chevalet. Basse, même les notes douces grésillent; haute, seules les
  fortes.
- **pincer** : un pincement doux ou dur. Sur un instrument frotté, la pression de l’archet.
- **position** : où la corde est pincée.
- **maintien** : combien de temps les cordes résonnent.
- **brillant** : combien de temps elles gardent leurs aigus.

## Bourdons

- **tarabs** : le volume des cordes sympathiques.
- **chikari** : frappe les cordes de bourdon à chaque nouvelle note.
- **éclosion** : combien de temps sonnent les cordes sympathiques et de
  bourdon, de quatre fois moins à quatre fois plus. Elles sonnent à peu près
  aussi fort dans les deux cas.
- **première** : la première corde du tanpura : **pa**, **ma**, **ni** ou
  **sa** une octave plus bas. Les trois autres sont Sa, Sa et Sa une octave
  plus bas.
- **cycle** : en combien de temps le tanpura pince ses quatre cordes avant
  de recommencer : **2**, **4**, **6** ou **8** temps. Il suit le tempo.

## Morceau

- **de la piste** : la piste dont le son fait sonner les cordes sympathiques
  et de bourdon, ou **non**. Seuls les modèles qui ont des cordes
  sympathiques ou de bourdon sonnent ainsi : sitar, shamisen, sarod, les
  veenas, e-sitar et les instruments frottés.
- **résonance** : la force avec laquelle cette piste les secoue.
- **écouter** : à 0, les cordes restent sur **sa** et **gamme**. Montez-le et
  elles se réaccordent sur les notes que joue la piste, en glissant vers
  chaque nouvel ensemble. Un peu apprend la tonalité du morceau sur
  plusieurs mesures ; à fond, elles suivent chaque accord. Les cordes de
  bourdon se posent sur la note sur laquelle la piste s’appuie le plus.

Les cordes suivent l’accordage de la piste. Dans un accordage de plus de
douze notes par octave, **gamme** prend les degrés les plus proches de
l’accordage, et **écouter** entend les degrés de l’accordage lui-même.

## Mains

- **meend** : avec une seule voix, une nouvelle note glisse depuis la
  précédente sur la même corde au lieu d’être pincée à nouveau.
- **gamak** : secoue la note rapidement. La molette de modulation fait de
  même.
- **voix**, **vélocité**, **octave**, **bend** et **volume**.

La pression (du clavier, ou de chaque note avec le MPE) tire la corde de
côté et monte la note jusqu’à un ton entier.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Buzz, Tarbs,
etc.) et les noms s’affichent sur les boutons, dans les lanes d’automation et
dans la liste de mappage du contrôleur. Ils sont à zéro au chargement d’un
patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace les notes jouées ensuite, 24
demi-tons à 1. Le modèle, l’accordage, le sa, la gamme, la première corde, le
cycle, les voix, l’octave, le bend et la piste ne sont pas accessibles. Un changement de
pincement, de position, de chikari ou de vélocité s’entend à la note suivante ;
le reste bouge tout de suite, comme le pincement sur un instrument frotté.
Touchez le nom d’une macro pour la renommer.

## Astuces

- Réglez **sa** sur la tonalité du morceau et **gamme** sur sa gamme : les
  cordes sympathiques sonnent avec la mélodie.
- Mettez Sympath sur une piste sans clips, pointez **de la piste** vers la
  batterie ou le groupe et montez **écouter** : le morceau reçoit un halo de
  cordes dans sa propre tonalité. Les patches **morceau** pointent vers la
  piste 1.
- Le tanpura ne pince que tant qu’une note est tenue : tenez Sa aussi
  longtemps que le bourdon doit durer.
- **Sitar** n’a qu’une voix : jouez lié pour le meend, et appuyez plus fort
  pour tirer une note vers le haut.
