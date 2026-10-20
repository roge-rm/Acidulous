# Filament

> Des cordes modélisées : pincées, au médiator, frappées, frottées ou soufflées.

Filament ne joue pas d’enregistrement. Il simule une corde qui vibre, alors il
réagit comme une vraie.

## L’excitateur

**excitateur** est ce qui met la corde en mouvement : pincer, médiator, marteau,
archet, souffle ou **entrée**, l’entrée en direct, alors vous pouvez jouer une
corde avec un micro.

- **où** - l’endroit de la corde où elle est excitée. C’est ce qui change le
  plus le timbre : près du bout, c’est mince et nasal, près du milieu, c’est
  rond.
- **dureté** - la dureté du marteau.
- **rugueux** et **longueur** - le bruit de l’excitation et sa durée.

## La corde

- **couplage**, **désaccord** et **écart** - deux cordes par note, un peu
  écartées, comme une douze-cordes ou le registre du milieu d’un piano.
- **étouffoir** - un feutre posé sur la corde à l’endroit de votre choix, bon
  pour les sons de guitare étouffés.

## La caisse

Quatre résonances tiennent lieu de caisse de l’instrument. **taille** les
déplace, **amortir** les élargit et **mélange** règle combien vous les
entendez. Il y a aussi des **cordes** sympathiques, sous **sympathie**, qui
résonnent avec ce que vous jouez.

## Modulation

Deux enveloppes et deux LFO qui peuvent se synchroniser au tempo, vers huit
rangées de matrice (source, destination, intensité).

Les enveloppes ne font rien tant que vous ne les routez pas. Une des sources
est **anneau**, le niveau auquel la corde résonne en ce moment, alors la corde
peut réagir à elle-même.

Les destinations comprennent **maintien**, **ton**, **position**, **pression**,
**étouffoir**, **tension** et **désaccord**. **brillant** change l’excitateur
(le grain, ou la dureté d’un marteau), et **grésil** et **volume** agissent par
note. **corps**, **satur.** et **sympathie** s’appliquent à tout l’instrument
et suivent la dernière note.

## Vibrato

Le groupe **vibrato** de la page **mod** donne à chaque note son propre
vibrato. **prof.** est en cents et est coupé à zéro, **vitesse** règle sa
rapidité et **délai** le temps qu’il met à arriver après le début de la note,
alors une note tenue peut commencer droite et s’épanouir.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Archet appuyé,
Plus brillant, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace tout l’instrument, 24
demi-tons à 1. L’excitateur, les étages de raideur, l’interrupteur et
l’accord de la sympathie et l’interrupteur de la caisse ne sont pas
accessibles. Un changement de longueur ou de vélocité s’entend à la note
suivante. Touchez le nom d’une macro pour la renommer.

## Astuces

- Changez **où** en premier, avant le filtre ou la caisse.
- Un LFO lent sur **position** donne vie à un pincement statique.
- Si une note résonne trop longtemps, raccourcissez le déclin plutôt que de
  baisser les choses.
