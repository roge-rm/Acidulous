# Réglages
<!-- desktop: Sur un Mac, **Réglages…** et **À propos…** sont aussi dans le menu Acidulous, et **Cmd+,** ouvre les réglages. Le menu **Édition** a annuler, rétablir et copier-coller des notes, et **Fenêtre** a placer dans le Dock, réduire/agrandir et plein écran. -->

## affichage

- **thème** - sombre, clair, contraste élevé ou comme le téléphone. Le contraste
  élevé, c’est du blanc sur noir, avec des couleurs plus vives et un contour
  autour de chaque commande.
- **langue** - la langue de l’appli, choisie dans une liste : celle du
  téléphone, l’anglais, ou le français du Canada ou de France (les mêmes mots;
  la France met une espace fine avant ; ! et ?). Pas dans un navigateur, qui
  utilise la sienne.
- **taille** - agrandit tout, en quatre crans.
- **taille du second écran** - la même chose pour le second écran. Seulement là quand l’appareil a un second écran.
<!-- desktop: - **échelle d’écran** - la taille à laquelle toute la fenêtre est dessinée. **système** prend le réglage de l’ordinateur. -->
- **rester allumé** - activé, l’écran reste allumé pendant la lecture.
- **second écran** - activé par défaut. Seulement là quand l’appareil a un second écran. Voir plus bas.
- **mises à jour** - activé, l’appli cherche une nouvelle version une fois par
  jour, en demandant à GitHub la dernière sortie, et affiche une ligne en haut de
  l’écran du morceau quand il y en a une. GitHub voit la version de l’appli et
  votre adresse internet, comme pour n’importe quelle page web. Absent quand
  F-Droid a installé l’appli, puisque F-Droid vous
  prévient lui-même, et dans le navigateur.
- **clavier** - **touches…** ouvre la liste des raccourcis, où vous pouvez les
  changer et choisir comment les lettres jouent des notes. Voir [Un clavier](11-keyboard.md).

## audio

<!-- desktop: - **sortie** - la sortie par laquelle jouer, ou celle du système par défaut. Sous Windows, le pilote propre d’une interface est aussi dans la liste, marqué **faible latence**. C’est le chemin le plus rapide vers l’interface, et pendant qu’il joue, les entrées de l’enregistreur sont celles de cette interface, deux à la fois. -->
- **tampon** - serré, équilibré ou sûr. Serré a la latence la plus basse mais
  peut craquer sur un téléphone lent, et sûr donne plus de temps au téléphone.
  Si le son coupe encore, le tampon grandit tout seul, puis redescend à la plus
  petite taille que l’appareil supporte, et s’en souvient pour la prochaine
  fois.
- **voix** - combien de notes une piste peut tenir à la fois. La note la plus
  ancienne est coupée en premier.
- **cœurs** - sur combien de cœurs les pistes sont calculées à la fois.
  **auto** utilise tous les cœurs rapides du téléphone sauf un, qu’il laisse à
  l’écran. **1** calcule toutes les pistes sur le même cœur. Un morceau léger
  reste sur un seul cœur de toute façon.
- **qualité** - ce qu’on sacrifie quand le téléphone n’arrive pas à suivre.
  **légère** :
  - fait tourner l’**amp** et la **distortion** sans suréchantillonnage (environ
    la moitié du coût de l’amp)
  - réduit de moitié la taille de la **reverb** (environ la moitié de son coût)
  - réduit de moitié les partiels des sons **Resonance** qui en utilisent plus
    de douze
  - réduit de moitié les piles d’unisson de **Trinity** (jamais moins de deux),
    et laisse sonner au plus six notes relâchées à la fois, en faisant
    disparaître vite les queues plus anciennes. Les notes tenues ne sont pas
    touchées
  - réduit de moitié le nombre de grains dans **Pollen**

  **auto** passe en légère tout seul quand le téléphone peine. Les notes que
  vous tenez gardent la qualité avec laquelle elles ont commencé, et seules les
  queues que vous avez déjà lâchées peuvent être écourtées.

  Les exportations et le figeage utilisent toujours la pleine qualité.

## enregistrer

- **résolution** - la résolution en bits des enregistrements et des
  exportations.
- **décompte** - les mesures de clics avant le début de l’enregistrement.
- **quantifier** - place ce que vous jouez sur la grille du clip. **non** laisse
  les notes exactement où vous les avez jouées, et **force** ne les déplace
  qu’en partie.
- **prise** - **ajouter** met ce que vous jouez avec les notes déjà là.
  **remplacer** enlève les notes que la tête de lecture croise à partir de
  votre première note, donc il reste ce que vous avez joué. Dans les deux cas,
  la prise compte pour une seule étape d’annulation.
- **départ** - **à la lecture** enregistre dès que vous appuyez sur jouer.
  **1re note** attend : armez, puis jouez une note et le morceau démarre avec
  elle, sans décompte.
- **passages** - **boucle** continue d’enregistrer en boucle jusqu’à ce que vous
  arrêtiez. **une fois** arrête l’enregistrement après un passage du clip et le
  laisse jouer.

## morceaux

Ce avec quoi un nouveau morceau commence : le tempo, le chiffrage, la machine de
la première piste, et s’il commence avec une gamme réglée. Choisissez **Aucune**
comme machine et un nouveau morceau commence sans aucune piste.

## second écran

Sur un appareil avec un second écran intégré, comme le Retroid Pocket Duo, l’appli s’ouvre sur les deux. L’écran du haut est l’appli comme d’habitude, et celui du bas le suit. Désactivez **second écran** dans les réglages pour n’utiliser que celui du haut.

Avec un éditeur ou une piste en haut, l’écran du bas a des onglets sur son bord supérieur : **clavier** (**pads** sur une boîte à rythmes), qui est la bande de jeu avec l’octave, les puces d’accords, de gamme et d’arpège et les molettes, et **panneau**, qui est le panneau de cette machine. Avec l’écran du morceau en haut, les onglets sont **mix** et **fx**. L’écran du bas se souvient de son onglet pour chaque sorte d’écran du haut. Avec une piste en haut, sa barre de patch passe en haut de l’écran du bas : le nom du patch, l’enregistrement et la liste à gauche, et les flèches et la marque qui replie le panneau de l’écran du haut à droite, près des onglets.

Sur **clavier**, la puce **piano | grille**, réglée par piste, choisit entre un piano, d’abord à deux rangées espacées d’une octave, et une grille de pads. Les rangées de la grille sont espacées d’une quarte, avec la fondamentale en bas à gauche. Tant qu’une gamme joue, la puce **gamme | toutes**, la même pour toutes les pistes, choisit entre seulement les notes de la gamme et toutes les notes, les autres étant atténuées. Sur la grille isomorphe, les pads restent en place et **gamme** laisse vides les pads hors gamme.

Maintenez **grille** pour ouvrir la fenêtre de la grille. **colonnes** et **rangées** règlent le nombre de pads, **disposition** la façon dont une gamme est placée. **isomorphe** est un demi-ton par colonne, et **rangée** est le nombre de demi-tons par rangée. **gamme** a seulement les notes de la gamme, et **rangée** est le nombre de notes de la gamme par rangée. **rangées piano** met les notes de la gamme en bas et les notes entre elles au-dessus. **par défaut** la remet à 6 sur 4, isomorphe.

Maintenez **piano** pour ouvrir la fenêtre du piano. Sous l’éditeur, maintenez plutôt l’octave entre ◀ et ▶. **rangées** est le nombre de claviers empilés, jusqu’à quatre (l’éditeur en a toujours un). **longueur** est le nombre d’octaves de chacun, et **auto** en met autant qu’il en tient confortablement. **noms** nomme les touches C, toutes les touches ou aucune. Sous **rangées**, chaque rangée a un bouton pour le nombre d’octaves au-dessus ou au-dessous de l’octave choisie, **rangée 1** en bas. **par défaut** remet deux rangées espacées d’une octave, la longueur auto et les C nommés. Maintenez ◀ ou ▶ et touchez une rangée pour déplacer seulement cette rangée d’une octave; toucher ◀ ou ▶ seul déplace toutes les rangées ensemble.

Ce que l’écran du bas montre quitte celui du haut : avec **clavier** (ou **pads**) en bas, l’éditeur du haut n’a plus de bande de jeu, et le rouleau gagne cette hauteur. Avec **panneau** en bas, celui du haut garde la bande et perd le panneau, et le rouleau gagne la place, en largeur ou en hauteur. Le panneau du bas remplit l’écran, ses cartes se partageant la place également et les boutons ajustés à leur taille.

Sur **mix**, chaque tranche a le panoramique et les deux envois en boutons, et le général montre ses deux envois. Quand les pistes ne tiennent pas en largeur, le mixage revient à la taille normale. Sur **fx**, les puces **envois**, **général** et le nom de la piste choisissent ce qui est montré : les deux envois, les inserts du général ou ceux de cette piste. Touchez encore la puce de la piste pour la piste suivante. Sur un petit écran du bas, ils s’affichent un à la fois, et les numéros à droite choisissent lequel.

Les touches et la manette suivent le dernier écran touché, et jouent la même piste sur l’un comme sur l’autre. Pour les déplacer sans toucher l’écran, appuyez sur **L3** (le stick gauche) ou **Ctrl+Tab** (**Alt+O**). Pendant que vous tapez dans un champ de l’écran du bas, les touches y restent jusqu’à ce que vous ayez fini.

## TalkBack

TalkBack s’active dans les réglages du téléphone. Quand il est actif, chaque
commande dit ce qu’elle est et à quoi elle est réglée. Sur un bouton ou un
curseur, balayez vers le haut ou le bas pour le changer. Tout ce que vous
maintiendriez pour l’ouvrir, comme les réglages d’un clip, est dans le menu
d’actions de TalkBack.

La grille de notes dit combien de notes a un clip, ainsi que la plus basse et
la plus haute, mais pas chaque note. Pour ajouter des notes, enregistrez-les
depuis le clavier.

Avec TalkBack actif, la grille du morceau montre autant de scènes qu’il en
entre et ne défile pas de côté. **Scènes précédentes** et **Scènes suivantes**
au-dessus avancent d’une page à la fois. Quand la dernière page est pleine,
ajoutez une scène depuis le menu d’une scène.
