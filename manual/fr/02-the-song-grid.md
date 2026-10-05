# La grille du morceau
La grille sert d’arrangeur ou de lanceur de clips. Passez de l’un à l’autre
avec le bouton du coin supérieur gauche de la grille.

## En arrangeur

C’est le mode par défaut. Le morceau joue scène par scène, de gauche à droite.
Chaque scène joue tous ses clips, se répète autant de fois que l’indique son
en-tête, puis la scène suivante commence.

- Touchez l’en-tête d’une scène pour commencer là. Avec **⟳**, cette scène se
  répète, et avec **⇥ fin**, le morceau joue à partir d’elle jusqu’à la fin et
  s’arrête.
- Maintenez l’en-tête d’une scène pour son menu : réglages, insérer, dupliquer,
  supprimer et déplacer à gauche ou à droite.
- Touchez le bouton de boucle à gauche de la barre du bas pour boucler tout le
  morceau ou seulement la scène en cours. Maintenez-le pour choisir **⟳** pour
  boucler sans fin ou **⇥ fin** pour jouer une fois jusqu’au bout et arrêter.
- Après un arrêt, jouer reprend au début du morceau.
- L’affichage au-dessus de la barre du bas montre la scène qui joue, à quelle
  répétition elle en est (**×1/2** est la première de deux) et la mesure et le
  temps. Le temps à sa droite montre où vous êtes dans le morceau et sa durée.
  Touchez-le pour voir plutôt le temps qui reste, ou le temps depuis que vous
  avez appuyé sur jouer, et touchez encore pour revenir.
- Si quelque chose continue de sonner, maintenez jouer pour couper chaque note,
  écho et queue. **Panique** dans À propos… fait la même chose.
- La lecture s’arrête d’elle-même quand un appel entre, qu’une autre appli se
  met à jouer ou qu’on débranche les écouteurs.
- Si Acidulous se ferme sans prévenir, il le dit à la prochaine ouverture et
  offre de partager un rapport. Les rapports restent sur le téléphone sauf si
  vous en partagez un, et le dernier est aussi dans À propos.

### Le tempo d’une scène

Dans les réglages d’une scène, **tempo** peut suivre le morceau ou être propre
à la scène, soit en sautant au début de la scène, soit en glissant sur sa
première mesure.

**Rampe à la fin** amène le tempo au bpm réglé par **vers** sur les dernières
mesures réglées par **sur**, pour ralentir vers la scène suivante ou accélérer
sur toute une scène. Ça se fait au dernier passage de la scène, et la scène
suivante commence à son propre tempo. Une scène avec une rampe montre ↘ ou ↗
dans son en-tête, et un export MIDI l’écrit comme un changement de tempo à
chaque temps.

## Les pistes

Touchez le nom d’une piste pour son menu : changer de machine, réglages,
renommer, figer, dupliquer et supprimer. Maintenez le nom, ou choisissez
**Réglages…**, pour les réglages de la piste :

- **nom** et **couleur**. Sinon, la couleur d’une piste vient de sa place.
- **transposer** - déplace chaque note vers le haut ou le bas pendant qu’elle
  joue, celles du clip comme celles de vos doigts. Le clip garde ce qui est
  écrit, donc revenir à 0 remet la partie comme avant. Pas sur les boîtes à
  rythmes.
- **accordage** - celui du morceau, ou celui de la piste. Voir
  [Accordages](05-effects-and-mixing.md).
- **vélocité** - **telle que jouée**, ou toutes les notes à une même vélocité.
- **swing** - celui du morceau, ou une quantité propre à la piste.
- **sortie midi** et **canal** - **non**, **les deux** (la machine et le
  matériel) ou **seule** (le matériel seulement).
- **sortie** - la sortie principale ou un des groupes du mixage.

Les deux derniers sont aussi sur la tranche de mixage de la piste.

## En lanceur

Touchez le bouton du coin et la grille devient un lanceur de clips, où chaque
piste joue son propre clip, de n’importe quelle scène.

- Touchez un clip pour le lancer. Il part sur la prochaine ligne de temps pour
  tomber en mesure.
- Le bouton **q :** règle ce qu’il attend. **fin** attend que le clip qui joue
  termine sa boucle, et les autres attendent un nombre de mesures.
- Touchez l’en-tête d’une scène pour passer à cette scène. Ses clips partent
  et toutes les autres pistes s’arrêtent, sur la même ligne. Un clip qui joue
  déjà continue.
- Touchez un clip qui joue pour l’arrêter à la fin de sa boucle. Touchez
  arrêter deux fois pour tout arrêter.
- Chaque piste garde sa propre position, et arrêter les laisse où elles sont.
  L’affichage au-dessus de la barre du bas montre où en est chacune, et à
  droite le temps depuis que vous avez appuyé sur jouer.

### Boucler

Dans le lanceur, une case vide est un looper.

- Touchez une case vide. Elle devient un clip, part sur la prochaine ligne et
  enregistre ce que vous jouez. Son bord pulse en rouge pendant
  l’enregistrement.
- Touchez-la encore pour fermer la boucle sur la barre de mesure la plus
  proche. Sinon, elle se ferme d’elle-même à 16 mesures.
- Une fois fermée, elle continue d’enregistrer par-dessus elle-même à chaque
  tour, avec un bord rouge fixe. Touchez pour arrêter d’y ajouter, et touchez
  encore pour en ajouter.
- Pour que les boucles se ferment à une longueur fixe, choisissez-en une sous
  **durée des boucles enregistrées** dans la fenêtre **q :**.

Le résultat est un clip normal. Touchez-le deux fois pour le modifier.

## Le zoom

Glissez à deux doigts pour vous déplacer dans la grille et pincez pour
agrandir ou réduire les cases. Un doigt ouvre et lance toujours les clips.

Sur une tablette ou une grande fenêtre, les cases grandissent pour remplir
l’écran, jusqu’à trois fois leur taille, tant que vous ne pincez pas.

## Les réglages d’un clip

Maintenez un clip pour ses réglages : sa longueur en mesures, muet et la
grille sur laquelle il s’aligne.

**Copier, couper, coller et vider** sont en haut. Vous pouvez maintenir
n’importe quelle case pour les avoir, même une vide, pour coller un clip dans
une autre scène ou piste. Coller et vider demandent d’abord s’il y a un clip
là. Couper ne demande pas, puisque le clip reste dans le presse-papiers.

Une copie a les notes, l’automatisation et les réglages, longueur comprise.
Elle n’a pas l’audio figé, donc un clip collé joue sa machine jusqu’à ce que
vous le figiez de nouveau. Les enregistrements d’une piste audio suivent, eux.

## Ce qu’une case montre

Le nombre de mesures est dans le coin, avec **1** pour un clip joué une fois et
**M** pour un clip muet. Une case d’une piste audio montre sa forme d’onde et
combien de ses quatre couloirs ont un enregistrement. Elle devient **ambre**
si l’un d’eux a été enregistré à un autre tempo que celui de la scène.

## Figer

Figer rend un clip en audio et joue cet audio au lieu de la machine, ce qui
libère du processeur pour le reste. Vous pouvez figer un clip, une scène
entière ou une piste entière à partir des menus.

Les deux effets d’insertion de la piste sont figés aussi, donc un clip figé ne
coûte presque rien à jouer. Le mixage fonctionne toujours : fader, panoramique,
envois et muet.

La queue (réverbération, délai, relâchement long) est rendue aussi, jusqu’à
huit secondes, et elle résonne sur la boucle suivante et après l’arrêt du clip
comme le ferait la machine en direct.

Si une scène fait une rampe vers un nouveau tempo, ses clips figés sont étirés
dans le temps le long de la rampe sans changer de hauteur.

Changer la machine, un des effets ou le tempo rend un figé périmé, et le clip
reçoit une marque. Défigez-le ou figez-le de nouveau.

## Quand le téléphone ne suit pas

Si le moteur commence à prendre du retard, les pistes qui coûtent le plus
s’allument en rouge, tout comme l’indicateur de charge dans l’en-tête. Rien ne
s’arrête.

Une piste ne s’allume que si le moteur est en retard et que cette piste pèse
lourd dans la charge. La figer règle d’habitude le problème.
