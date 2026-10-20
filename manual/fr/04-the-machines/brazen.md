# Brazen

> Des cuivres modélisés, de la trompette au tuba, ou tout un pupitre.

Brazen est un modèle d’instrument de cuivre : des lèvres qui vibrent contre un
tube terminé par un pavillon.

## Souffler

- **pression** - la force du souffle. C’est la commande principale, et ce
  n’est pas un bouton de volume. Trop peu et rien ne sonne, plus et la note
  part, plus encore et elle devient plus brillante jusqu’à l’octaviation.
- **souffle** - le bruit d’air.
- **mordant** et **tension** - la tension des lèvres. Avec la pression, ils
  décident sur quelle note du tube vous tombez, comme l’embouchure d’un vrai
  musicien.
- **amorti**, sous **lèvres** - l’amortissement des lèvres.

## Le tube

- **tuba→trp.** - la longueur du tube, du tuba à la trompette.
- **pavillon** - l’évasement du tube, qui change sa brillance.
- **perte** - l’énergie que le tube perd.
- **cuivré** - l’éclat des cuivres joués fort.

## Un pupitre

**cohésion** et **dérive** changent un musicien en plusieurs qui jouent presque
ensemble. **grogne** et sa **vitesse** ajoutent un grognement dans
l’embouchure.

## Mono

Avec **mono**, une note jouée pendant que la précédente est encore tenue est
liée à elle et glisse pendant le temps de **porta.**. Une note jouée après avoir
relâché est attaquée à la langue, et la précédente résonne en dessous.

## Crescendo et flutter

Le groupe **crescendo** de la page **joueur** fait bouger le souffle au début
de chaque note. Montez **intensité** et le souffle part bas et monte jusqu’à la
pression réglée, alors à 1 la note sort presque de rien. Baissez-la et le
souffle part au-dessus de la pression et y redescend, un accent. **temps** règle
sa durée. Une note liée continue le crescendo qu’elle a rejoint.

Le groupe **flutter** roule la langue à sa **vitesse** et hache l’air et les
lèvres, le bourdonnement d’une trompette en flatterzunge. Il est séparé du
grognement et les deux peuvent jouer ensemble.

## Macros

La page **jeu** a huit boutons macro. Un patch peut les nommer (Souffle,
Cuivré, etc.) et les noms s’affichent sur les boutons, dans les lanes
d’automation et dans la liste de mappage du contrôleur. Ils sont à zéro au
chargement d’un patch, ce qui donne le patch tel qu’il a été fait.

La page **macros** règle ce que fait chacune. Une macro a quatre routes, une
destination (**vers**) et une quantité (**prof.**, de -1 à 1) pour chacune.
Tourner le bouton déplace la destination de bouton fois quantité, sur toute la
course de cette commande à 1. La hauteur déplace tout l’instrument, 24
demi-tons à 1. Le type de sourdine, les musiciens, le type de filtre, le mode
et les pas d’accord ne sont pas accessibles. Un changement de crescendo, de
portamento, d’écart, d’épars, de délai de vibrato ou de vélocité s’entend à
la note suivante.
Touchez le nom d’une macro pour la renommer.

## Astuces

- Les gros instruments demandent plus de pression. Un tuba se joue en soufflant
  fort.
- Pour un crescendo sur chaque note, prenez le groupe **crescendo**.
  Automatisez la pression pour en faire un sur une phrase. Les deux changent le
  timbre autant que le niveau.
- Partez d’un son et changez **tuba→trp.** d’abord.
