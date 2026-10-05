# Un clavier
Acidulous fonctionne avec un clavier intégré, USB ou Bluetooth.

Appuyez sur **Maj+/** (ou **Alt+Q**) n’importe quand pour voir les touches qui
fonctionnent sur l’écran où vous êtes.

## Mode jeu

Les lettres lancent soit des raccourcis, soit des notes. **`** (ou **Sym**)
passe de l’un à l’autre, tout comme toucher le numéro d’octave dans la bande du
clavier de l’éditeur. Le numéro est allumé quand les lettres jouent des notes.

En mode jeu :

- **A S D F G H J K L** sont les touches blanches, et **W E T Y U O P** les
  touches noires entre elles, comme sur un piano. A est un C.
- **Z** et **X** descendent et montent d’une octave. Dans l’éditeur, elles
  déplacent aussi le clavier à l’écran.
- **C** et **V** rendent les notes plus douces et plus fortes.
- Maj joue une note une octave plus haut.

Les notes vont à la piste du dernier clip que vous avez ouvert, comme avec un
clavier MIDI, donc elles s’enregistrent et l’accord, la gamme et l’arpège de la
piste s’appliquent. Sur une
boîte à rythmes, les touches sont ses pads, dans l’ordre.

Espace joue et arrête toujours, et tout ce qui utilise Ctrl ou Alt fonctionne
toujours comme raccourci.

### Disposition tracker

Un clavier complet a de la place pour deux octaves. Choisissez **tracker** sous
**notes** dans la fenêtre des touches (voir **Changer les touches** plus bas) :

- **Z** à **/** est l’octave du bas, avec **S D G H J** comme touches noires.
- **Q** à **P** est l’octave du haut, avec la rangée des chiffres comme touches
  noires.
- **-** et **=** changent l’octave, et **[** et **]** la force de frappe.

## Raccourcis

- jouer / arrêter - **Espace**
- enregistrer - **R**, ou **Alt+R**
- boucle - **L**, ou **Alt+L**
- annuler - **Ctrl+Z**, ou **Alt+Z**
- rétablir - **Ctrl+Maj+Z**, ou **Alt+Y**
- tout arrêter - **Ctrl+.**, ou **Alt+P**
- le clavier joue des notes - **`**, ou **Sym**
- sauvegarder - **Ctrl+S**, ou **Alt+S**
- menu fichier - **F**, ou **Alt+F**
- pages mixage et performance - **M**, ou **Alt+M**
- manuel - **F1**, ou **Alt+H**
- la liste des touches - **Maj+/**, ou **Alt+Q**
- retour - **Échap**
<!-- desktop: - plein écran - **F11** -->

Dans l’éditeur :

- page précédente / suivante - **[ et ]**, ou **Alt+B et Alt+N**
- piste précédente / suivante - **Maj+[ et Maj+]**
- dessiner ou sélectionner - **D**, ou **Alt+D**
- vue des pas - **T**, ou **Alt+T**
- verrouiller les pas - **K**, ou **Alt+K**
- générer des notes - **G**, ou **Alt+G**
- quantifier - **Q**, ou **Alt+U**
- replier le panneau - **P**, ou **Alt+V**
- replier le clavier - **B**, ou **Alt+J**

La première touche est pour un clavier complet et la deuxième pour un petit.
Les touches sans Alt ni Ctrl ne servent de raccourcis que lorsque le mode jeu
est désactivé.

Dans une fenêtre à onglets, page précédente et page suivante passent d’un
onglet à l’autre.

## Se déplacer

Les flèches, ou des balayages sur un pavé tactile, passent d’une commande à
l’autre. Tab et Maj+Tab aussi, et un anneau montre où vous êtes. **Entrée**
appuie sur un bouton ou un interrupteur.

Sur un bouton rotatif ou un curseur, **Entrée** le saisit et l’anneau devient
rose. Les flèches le tournent alors, Maj+flèches le tournent finement, Pg préc
et Pg suiv à grands pas, et Origine et Fin vont à l’un ou l’autre bout.
**Entrée** ou **Échap** le relâche. **+** et **-** tournent un bouton sans le
saisir.

Tout ce que vous maintiendriez offre les mêmes choix avec **Alt+Entrée** ou la
touche Menu, comme les réglages d’un clip, le menu d’une scène ou la liste des
valeurs d’un bouton.

Une fenêtre ouverte au clavier vous place sur sa première commande, et
**Échap** la ferme.

## Dans l’éditeur

La grille de notes, les couloirs de notes en dessous et les couloirs
d’automatisation prennent chacun **Entrée** pour commencer à modifier, et un
curseur rose apparaît. **Échap** arrête.

Dans la grille de notes :

- les flèches déplacent le curseur d’un pas de grille, ou d’un demi-ton. Pg préc
  et Pg suiv le déplacent d’une octave.
- **Entrée** ajoute ou enlève une note, comme un toucher.
  **Suppr** enlève la note sous le curseur.
- **Maj+Gauche** et **Maj+Droite** raccourcissent et allongent la note sous le
  curseur.
- **Alt** et une flèche déplacent la note avec le curseur.

Dans un couloir de notes, Gauche et Droite vont de note en note, et Haut et Bas
changent la valeur de la note, finement avec Maj. Quand les paroles sont
affichées, Entrée les ouvre.

Dans un couloir d’automatisation, Gauche et Droite avancent d’un pas de grille,
et Haut et Bas règlent la valeur à cet endroit, en ajoutant un point s’il n’y
en a pas.

Chaque changement est une étape d’annulation, comme au toucher.

## Une manette de jeu

Les boutons d’une manette fonctionnent comme les touches, donc l’appli peut
s’utiliser sans toucher l’écran, sur une console portable comme la Retroid
Pocket.

- **croix directionnelle** - passe d’une commande à l’autre, et déplace le
  curseur dans l’éditeur.
- **A** - appuie, comme Entrée. Sur un bouton rotatif, il le saisit, la croix
  le tourne, et **A** de nouveau le relâche.
- **B** - retour : ferme une fenêtre, ou quitte l’éditeur.
- **X** - ce que fait un appui long : la liste d’actions d’une commande.
- **Y** - jouer / arrêter.
- **L1** et **R1** - page précédente et suivante dans l’éditeur, et les onglets
  d’une fenêtre.
- **L2** et **R2** - piste précédente et suivante dans l’éditeur.
- **Start** - mode jeu.
- **Select** - le menu fichier.

- **stick gauche** - tourne le bouton ou le curseur en surbrillance, d’autant
  plus vite qu’il est poussé loin. Une poussée est une étape d’annulation.
- **stick droit** - une croix rapide : plus il est poussé loin, plus il va
  vite.

Si rien n’est en surbrillance, le premier appui sur la croix ou sur **A** met
quelque chose en surbrillance pour commencer.

**Start** active et désactive le mode jeu. En mode jeu, la manette est un
instrument pour la piste que joue le MIDI :

- la **croix directionnelle** et les quatre boutons sont huit notes de la gamme
  de la piste (sa puce de gamme, ou la tonalité du morceau, sinon majeur), dans
  le sens horaire à partir du bas pour chacun : la croix a les quatre premières,
  les boutons les quatre suivantes. Sur une boîte à rythmes, ce sont ses huit
  premiers pads.
- **L1** et **R1** changent l’octave.
- le **stick droit** plie la hauteur de côté et ajoute de la modulation vers le
  haut, et le **stick gauche** poussé vers le haut donne la pression.
- tirez **R2** ou **L2** en partie pendant que vous jouez pour régler la force
  des notes.
- **Select** joue et arrête, car **Y** est une note.

## Changer les touches

**Réglages › affichage › clavier › touches…** liste chaque raccourci avec ses
touches, et chacun peut en avoir deux. Touchez une touche, ou allez-y et
appuyez sur Entrée, puis appuyez sur la nouvelle touche ou combinaison. **+**
ajoute une deuxième touche.

Si la nouvelle touche appartenait déjà à autre chose, elle change de place, et
la fenêtre vous dit ce qui l’a perdue. **Alt+Entrée** sur une touche la retire.
**par défaut** remet toutes les touches comme elles étaient.

La carte **manette** liste les boutons d’une manette et ce que fait chacun.
Touchez-en un pour choisir dans une liste : **appuyer**, **retour**, **liste
d’actions**, **rien**, ou n’importe quel raccourci. **par défaut** remet ceux-ci
aussi. Les notes du mode jeu restent où elles sont.
