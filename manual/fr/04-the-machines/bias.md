# Bias

> Un quatre-pistes : des enregistrements placés le long du morceau, quatre couloirs à la fois.

Bias est la piste audio. Au lieu de notes, chacune de ses cellules joue des
enregistrements, quatre couloirs à la fois. Rendez deux prises muettes pour choisir
la troisième, ou réactivez un deuxième couloir pour doubler une voix.

## Sa place dans le morceau

Une cellule Bias est un clip normal, alors il n’y a pas de ligne de temps à
part. Une prise enregistrée sur quatre scènes devient quatre cellules qui
pointent toutes vers le même fichier, chacune à partir d’un endroit différent.
Rien n’est copié, et ça marche pareil dans l’arrangeur et dans le lanceur de
clips.

Une cellule dure ses mesures fois les répétitions de la scène et joue d’un
trait, alors une scène jouée deux fois joue une seule prise continue.

## Enregistrer

Touchez le point rouge à côté d’un couloir, armez l’enregistrement dans le
transport et appuyez sur jouer. Le morceau joue pendant l’enregistrement, et
les autres couloirs continuent de jouer.

À l’arrêt, la prise est coupée aux lignes de scène, une cellule par scène, avec
de nouvelles cellules dans les scènes où la piste était vide. L’enregistrement
complet reste aussi dans la bibliothèque de sons, alors vous pouvez annuler le
découpage et le placer à la main.

Un seul couloir enregistre à la fois. Si l’enregistreur a pris du retard et
laissé un trou dans la prise, elle n’est pas découpée et reste entière.

## Enregistrer en boucle et prises

Enregistrez pendant qu’une scène boucle et chaque passage devient une prise sur
ce couloir, empilée sur les précédentes. Vous entendez la plus récente. Si vous
arrêtez au milieu d’un passage, la nouvelle prise ne joue que là où elle a du
son et la précédente joue le reste. Chaque cellule garde sa propre pile.

Enregistrer sur un couloir qui a déjà une prise l’ajoute aussi à sa pile, si
elle a été enregistrée au même tempo sur la même longueur de scène. Sinon elle
la remplace.

La carte de chaque couloir sur la page montre quelle prise vous entendez, comme
**prise 2/3**. Touchez-la pour entendre la suivante. Annuler retire un passage
ou un choix.

## Choisir par phrase

Touchez un couloir qui a plus d’une prise et ses prises s’affichent en rangées
fines dessous, la prise 1 en haut, alignées dans le temps avec le couloir. Le
couloir lui-même montre ce que vous entendez. Des lignes pâles coupent le
couloir en phrases là où la plupart des prises se taisent, les mêmes phrases
pour toutes les prises.

- touchez une **phrase** sur le couloir pour la choisir, puis touchez la
  **rangée d’une prise** dans cette phrase pour entendre cette prise là.
  Toucher une rangée n’importe où choisit la phrase sous votre doigt
- glissez le long de la **rangée d’une prise** pour entendre cette prise sur
  exactement le passage glissé, pour un mot ou une respiration que les phrases
  ne séparent pas
- touchez le **numéro** d’une prise pour **partout**, qui la fait jouer sur tout
  le couloir, ou **supprimer la prise**, qui la retire de la pile. Là où on
  l’entendait, la prise qui a le plus de son à cet endroit joue à sa place
- touchez de nouveau la phrase choisie pour ranger les rangées

Les raccords entre prises font un fondu enchaîné tout seuls, à un point calme
tout près. Chaque choix est une étape d’annulation. L’enregistrement d’une prise
supprimée reste dans la bibliothèque de sons, comme tout enregistrement, et
vous pouvez l’y supprimer.

## Écouter chaque prise

Une phrase choisie, touchez **▶** à gauche de la règle. La phrase tourne en
boucle avec une seconde avant elle, chaque passage avec la prise suivante qui a
du son à cet endroit, et la prise qui joue s’allume sur sa rangée. Touchez une
rangée pour garder cette prise pour la phrase, ce qui arrête l’écoute. Touchez
**■** pour arrêter sans rien changer.

Seul ce couloir joue pendant l’écoute, à son niveau, même s’il est muet. Le
morceau continue comme avant, et le couloir le rejoint quand vous arrêtez.
Choisir une autre phrase, modifier le couloir ou quitter la cellule arrête
aussi l’écoute.

Au clavier, allez sur un couloir qui a des prises et appuyez sur **Entrée**.
Gauche et droite choisissent la phrase, haut et bas la prise, et Entrée la
garde. Pendant l’écoute, Entrée garde la prise qui joue. **Échap** arrête.

## Suggérer, aligner et mêler

Sous les rangées de prises se trouvent les outils de montage.

- **suggérer** choisit la meilleure prise dans chaque phrase, en une seule
  étape d’annulation. Chaque prise est notée sur la justesse de sa hauteur par
  rapport aux notes de l’accordage de la piste (celui de la chanson, sauf si la
  piste a le sien), la justesse de ses attaques par rapport au temps et aux
  autres prises, la saturation et le bruit de fond. Sur un couloir aligné sur
  un autre, les attaques de l’autre couloir donnent le temps. Les notes
  s’affichent en pâle au bout de chaque phrase sur les rangées, la meilleure
  allumée. Une prise beaucoup plus faible que les autres dans une phrase a 0
- **aligner** déplace ce couloir sur le jeu d’un autre couloir mot par mot,
  pour une doublure un peu en avance ou en retard. Chaque phrase est coupée en
  mots à ses attaques et à ses courts silences, et chaque mot bouge à part,
  d’au plus 50 ms. Les mots restent dans l’ordre et ne se chevauchent jamais.
  S’il y a plus d’un autre couloir, il demande lequel. Il affiche ensuite
  **aligné** : touchez-le pour remettre le couloir exactement comme avant. Un
  couloir d’une seule prise l’affiche aussi, quand un autre couloir a du son
- **mêler** joue toutes les prises du couloir ensemble, chacune à peu près au
  même niveau, et la somme au niveau d’une prise. C’est une doublure rapide ou
  une petite chorale. Touchez-le encore pour revenir à vos choix, qu’il garde
- **aligner les prises**, quand mêler est allumé, aligne chaque prise mot par
  mot avant de les jouer ensemble : sur le couloir sur lequel celui-ci est
  aligné, sinon sur le couloir tel que vous l’entendriez sans mêler.
  Touchez-le encore pour les remettre
- **étaler**, quand mêler est allumé, répartit les prises dans le champ
  stéréo, de toutes au centre jusqu’à la première et la dernière aux bords. Le
  niveau reste le même

Quand il mêle, le couloir dessine chaque prise en pâle et leur somme par-dessus.

## L’éditeur

Ouvrir une cellule montre les quatre couloirs :

- glissez le **corps** d’un couloir pour déplacer son début
- glissez l’une ou l’autre **extrémité, moitié du haut** pour le rogner
- glissez l’une ou l’autre **extrémité, moitié du bas** pour un fondu en entrée
  ou en sortie. Un couloir qui s’éteint pendant qu’un autre monte fait un
  fondu enchaîné
- touchez le numéro à gauche pour rendre un couloir **muet**, et le point
  en dessous pour l’armer

Sur une prise plus longue que sa cellule, les deux moitiés du bord droit
rognent, pour vous laisser une extrémité où faire le fondu.

## Niveaux, muets et automatisation

Le niveau et le muet de chaque couloir sont des paramètres de machine normaux,
alors vous pouvez les automatiser, les assigner à un contrôleur et les
enregistrer.

## Tempo

**tempo › prises** choisit si une prise joue à la vitesse où elle a été
enregistrée ou suit le tempo du morceau. Quand elle suit, elle est étirée sans
changer de hauteur. Sinon, elle commence sur la mesure et joue à sa propre
vitesse, et la cellule devient ambre.

Une boucle ajoutée depuis la bibliothèque avec **audio…** voit son tempo
calculé comme le fait Dice, d’après sa longueur et la place de ses coups. Elle
boucle pour remplir la cellule, et si son tempo n’est pas celui de la scène,
**tempo › prises** passe à suivre pour qu’elle joue au tempo du morceau.

## Jouer de la guitare au travers

**retour**, sous **tempo**, envoie l’entrée à la sortie de cette piste avant
ses effets, alors vous entendez un ampli dans la première insertion pendant
que vous jouez. L’enregistrement reste sec, alors vous pouvez changer l’ampli
plus tard.

Le retour est éteint par défaut. Utilisez des écouteurs ou une interface, car
sur le haut-parleur du téléphone il va faire du larsen.

**imprimé**, à côté, sert aux effets que vous voulez enregistrer dans la prise.
Ils passent avant l’enregistreur, alors ils y restent. Ce sont les deux mêmes
emplacements d’entrée que montre la fenêtre d’enregistrement.

## Aplatir

**comp** mélange les quatre couloirs (avec leurs niveaux, muets, fondus et les
prises qu’ils jouent) en un seul fichier dans le couloir 1. La couleur de bande du son n’est pas
incluse, et les enregistrements d’origine restent dans la bibliothèque.

## Sons

Les sons de Bias sont des supports d’enregistrement (cassette, bobine,
téléphone, cylindre de cire, etc.). Ils colorent le son à la lecture mais ne
changent jamais les enregistrements, alors essayez-les sans crainte. **Init**
rejoue le fichier tel quel.

## Astuces

- Enregistrez d’abord et décidez plus tard. Le son, le tempo, les fondus et
  même le découpage peuvent tous changer après coup sans toucher au fichier.
- Les prises peuvent être longues. Tout ce qui dépasse deux minutes est
  converti une fois en arrière-plan et lu depuis le stockage, alors une voix
  complète coûte à peu près autant qu’une courte.
