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

**comp** mélange les quatre couloirs (avec leurs niveaux, muets et fondus) en
un seul fichier dans le couloir 1. La couleur de bande du son n’est pas
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
