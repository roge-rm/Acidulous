# Réglages

## affichage

- **thème** - sombre, clair, contraste élevé ou comme le téléphone. Le contraste
  élevé, c’est du blanc sur noir, avec des couleurs plus vives et un contour
  autour de chaque commande.
- **octaves du clavier** - combien d’octaves montre le clavier sous l’éditeur. **auto** en montre deux sur un téléphone et jusqu’à cinq sur un écran plus large.
- **langue** - la langue de l’appli, choisie dans une liste : celle du
  téléphone, l’anglais, ou le français du Canada ou de France (les mêmes mots;
  la France met une espace fine avant ; ! et ?). Pas dans un navigateur, qui
  utilise la sienne.
- **taille** - agrandit tout, en quatre crans.
<!-- desktop: - **échelle d’écran** - la taille à laquelle toute la fenêtre est dessinée. **système** prend le réglage de l’ordinateur. -->
- **rester allumé** - activé, l’écran reste allumé pendant la lecture.
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
