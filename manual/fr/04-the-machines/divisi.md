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
demi-tons à 1. Le mode, l’accordage, l’octave et le bend ne sont pas
accessibles. Touchez le nom d’une macro pour la renommer.

## Astuces

- Un gros accord avec tous les registres à fond et à pleine vélocité devient
  fort. Baissez un peu la vélocité ou le volume quand vous empilez tout.
- **Para Brass** et **Brass Swell** partagent un seul filtre : tenez un accord
  et ajoutez-y des notes plutôt que de rejouer chaque note.
