# Effets et mixage
## Insertions

Chaque piste a deux emplacements d’effet en insertion. **fx** dans la barre du
bas de l’éditeur les affiche à la place du panneau de la machine.

Il y a vingt-neuf effets. Chacun a les réglages habituels plus un extra, affiché
dans la couleur d’accent. Chaque effet finit par **gain**, un réglage du niveau
de sortie, puisque monter le mélange peut changer le niveau.

Chaque effet a sa propre page ci-dessous.

## Temps

- [**Delay**](05-effects-and-mixing/delay.md) : des échos sur une valeur de
  note, qui peuvent s’atténuer pendant que vous jouez.
- [**Reverb**](05-effects-and-mixing/reverb.md) : une salle qui peut aussi se
  figer, se couper, miroiter ou s’écraser jusqu’à 8 bits.
- [**Grain**](05-effects-and-mixing/grain.md) : un nuage de courtes tranches de
  ce que la piste vient de jouer, qui peut se figer.
- [**Tape**](05-effects-and-mixing/tape.md) : un magnétophone usé, avec
  pleurage, scintillement, saturation et souffle, et un interrupteur qui le
  ralentit jusqu’à l’arrêt.
- [**Slicer**](05-effects-and-mixing/slicer.md) : découpe la piste en tranches
  sur le temps et en répète, inverse ou omet certaines.

## Timbre

- [**Eq**](05-effects-and-mixing/eq.md) : trois bandes et une pente.
- [**Filter**](05-effects-and-mixing/filter.md) : passe-bas, passe-bande ou
  passe-haut, déplacé par un LFO, le niveau du signal ou une autre piste.
- [**Width**](05-effects-and-mixing/width.md) : plus large, plus étroit, mono
  sous une fréquence ou tourné.
- [**Acid**](05-effects-and-mixing/acid.md) : le filtre de la basse acid sur
  n’importe quelle piste, ouvert par chaque note ou par un motif sur le temps.
- [**Mouth**](05-effects-and-mixing/mouth.md) : des voyelles, déplacées par un
  LFO, le niveau de la piste ou une autre piste.
- [**Spectral**](05-effects-and-mixing/spectral.md) : fige, étire, brouille
  ou robotise les fréquences de la piste.

## Saturation

- [**Distortion**](05-effects-and-mixing/distortion.md) : quatre sortes
  d’écrêtage et un réglage de polarisation.
- [**Amp**](05-effects-and-mixing/amp.md) : un ampli de guitare avec un baffle
  dont on peut changer la taille.
- [**Bitcrusher**](05-effects-and-mixing/bitcrusher.md) : moins de bits et une
  fréquence d’échantillonnage plus basse, avec une horloge instable si vous
  voulez.
- [**Magneto**](05-effects-and-mixing/magneto.md) : le son des formats d’un
  petit disque enregistrable, de l’original propre au tourbillon de la longue
  durée, recopié jusqu’à quatre fois.
- [**Formula**](05-effects-and-mixing/formula.md) : tapez une formule et elle
  façonne la piste, échantillon par échantillon.

## Niveau

- [**Compressor**](05-effects-and-mixing/compressor.md) : les réglages
  habituels, une entrée latérale de n’importe quelle piste et un pompage calé
  sur le tempo.
- [**Gate**](05-effects-and-mixing/gate.md) : une porte de bruit qu’une autre
  piste peut ouvrir.
- [**Swell**](05-effects-and-mixing/swell.md) : une compression vers le haut,
  sur une bande ou trois. Les passages doux montent rejoindre les forts.
- [**Smash**](05-effects-and-mixing/smash.md) : trois bandes écrasées des deux
  côtés. Les passages forts descendent, les doux montent.

## Mouvement

- [**Chorus**](05-effects-and-mixing/chorus.md) : deux à quatre voix
  désaccordées qui dérivent.
- [**Flanger**](05-effects-and-mixing/flanger.md) : un court délai qui balaie,
  avec une réinjection négative pour le son creux.
- [**Phaser**](05-effects-and-mixing/phaser.md) : deux à huit étages.
- [**Tremolo**](05-effects-and-mixing/tremolo.md) : le volume sur un LFO, ou un
  panoramique automatique.
- [**Rotary**](05-effects-and-mixing/rotary.md) : la cabine à haut-parleur
  tournant de l’orgue, lente, rapide ou sur le temps.

## Hauteur

- [**Shifter**](05-effects-and-mixing/shifter.md) : un décalage de fréquence,
  pour des sons métalliques et désaccordés.
- [**Harmonizer**](05-effects-and-mixing/harmonizer.md) : ajoute deux voix à
  des degrés de la gamme, pour qu’elles restent dans la tonalité.
- [**Resonator**](05-effects-and-mixing/resonator.md) : des cordes accordées
  dans une tonalité, qui résonnent avec la piste.
- [**Horn**](05-effects-and-mixing/horn.md) : la piste joue d’un cuivre,
  d’une clarinette, d’un hautbois ou d’une flûte, en suivant sa hauteur et son
  niveau.

## Effets d’entrée

Il y a deux autres emplacements d’effet sur l’**entrée**. Ils sont en haut de
la page **enregistrer** de la fenêtre d’enregistrement (sous **gravé dans la
prise**), et sur le panneau de Bias sous **imprimé**.

- Un effet **sur l’entrée** passe avant que quoi que ce soit d’autre entende
  l’audio, donc il est **gravé dans la prise**.
- Un effet **sur une piste** passe à la lecture, donc vous pouvez le changer
  plus tard.

Mettez donc un ampli sur l’entrée si vous avez choisi votre son, ou sur la
piste pour garder vos options. Les effets d’entrée gardent leur réglage
**mélange**.

## Le mixage

Le bouton de mixage ouvre une tranche par piste, plus la sortie principale. Les
onglets le long du bord gauche passent du mixage aux trois pages de jeu.

- **Fader et vumètre** pour chaque piste, avec muet et solo.
- **Deux niveaux d’envoi** par piste, vers deux effets partagés par tout le
  morceau.
- **pano**, et une rangée MIDI pour ce que la piste envoie.
- Un **∿ à côté du nom d’une piste** veut dire que quelque chose sur ce canal
  est automatisé. Touchez-le pour voir quels couloirs, et pour les vider de
  tous les clips de la piste. Les notes ne sont pas touchées.

Après les pistes viennent les **groupes**, si le morceau en a, et un bouton
**+ groupe**. En dernier vient la tranche principale, avec le fader général, la
**sat. limiteur** et la mesure de sonie. Dessous, une grille de boutons : les
deux envois en haut, les deux insertions générales (**fx1**, **fx2**) au
milieu, et le limiteur (**lim**) et le clic (**♩**) en bas. Touchez-en un pour
l’activer ou le désactiver, et maintenez un envoi ou une insertion pour choisir
son effet et le régler.

## Jeu

Les onglets le long du bord gauche du mixage sont **mix**, **tenir**, **pad**
et **direct**. Les trois derniers servent à jouer le morceau en direct.

Les effets de **tenir** et de **pad** agissent sur tout le mixage, après les
insertions générales, et ne sont actifs que pendant que vous les maintenez, sauf
si **verrou** est actif. Avec le verrou, un toucher active quelque chose et un
autre le désactive, et le pad reste où vous le laissez. Désactiver le verrou
relâche tout sur cette page.

Si le morceau a des groupes, **sur tout** choisit sur quoi les effets agissent :
tout le mixage ou un seul groupe, par exemple pour ne répéter que la batterie.
Touchez-le pour passer d’un groupe à l’autre.

Si vous enregistrez, tout ce que vous faites sur **tenir** et **pad** est
enregistré comme automatisation dans le clip de la dernière piste ouverte.
Arrêter le morceau relâche tout ce qui est maintenu.

### Tenir

- **répéter** boucle la dernière tranche du morceau. Les cinq boutons sont la
  longueur de la tranche, d’un temps jusqu’à une double croche, et vous pouvez
  glisser de l’un à l’autre sans lever le doigt. Pendant la lecture, la tranche
  commence sur le temps, donc elle reste en mesure.
- **gate** hache le son en mesure. Les cinq boutons sont la vitesse : croches,
  doubles croches, triples croches, ou triolets de croches et de doubles
  croches.
- **inverse** joue le dernier temps à l’envers, en boucle, en mesure avec le
  morceau. Maintenez-le avec une répétition et la répétition joue à l’envers.
- **arrêt** ralentit le morceau jusqu’à l’arrêt comme un magnétophone qui perd
  son courant, et repart quand vous relâchez. **arrêt** en dessous règle combien
  de temps ça prend.
- **montée** prépare un drop. Un passe-haut monte et du bruit monte dessous
  sur 1, 2 ou 4 mesures (**montée** en dessous), puis tout revient d’un coup
  quand vous relâchez.

### Pad

De gauche à droite, c’est un filtre : passe-bas à gauche du milieu et
passe-haut à droite. Vers le haut, c’est la part du mixage envoyée dans un
écho. Relâchez et l’écho continue de se répéter en s’éteignant. **écho** règle
son temps et **retour** sa durée.

**x** et **y** changent ce que fait le pad :

- **x : crush** fait de la gauche et de la droite un bitcrusher au lieu d’un
  filtre, avec moins d’échantillons à gauche du milieu et moins de bits à
  droite. Il écrase un mixage doux autant qu’un mixage fort.
- **y : nappe** envoie dans un court écho étalé qui ressemble plus à une grosse
  réverbération qu’à des répétitions. Il n’a pas de réglage de temps, et
  **retour** règle encore sa durée.

Sous le pad, **coupe grave**, **coupe médium** et **coupe aigu** retirent cette
partie du son pendant que vous les maintenez : sous 250 Hz, de 250 Hz à
2,5 kHz, et au-dessus de 2,5 kHz.

### Direct

Un bouton muet pour chaque piste (le même muet que celui du mixage), et
**fill**.

Pendant la lecture, un muet attend la mesure suivante et tombe dessus, donc les
pistes entrent et sortent en mesure. Le bouton est en contour jusque-là.
**muet** règle ce qu’il attend : une mesure, un temps ou rien (**direct**).
Quand le morceau est arrêté, les muets agissent tout de suite. Si vous
enregistrez, les muets sont enregistrés dans le clip de chaque piste.

Les notes réglées pour jouer sur fill ne jouent que pendant que **fill** est
maintenu. Le fill n’est pas enregistré.

## Insertions générales

**fx1** et **fx2** traitent tout le mixage, envois compris, avant le fader
général et le limiteur. Servez-vous-en pour ce qui s’applique à tout le morceau,
comme un EQ léger, un peu de compression de liaison, une saturation de bande ou
un grave plus étroit.

Un envoi s’ajoute à côté du mixage, et chaque piste choisit combien elle envoie.
Une insertion générale traite tout pareil. La reverb et le delay vont donc
d’habitude sur les envois, et l’EQ et la compression de tout le morceau sur les
insertions générales.

## Groupes

Un groupe est une tranche du mixage par laquelle des pistes peuvent passer. Il
n’a ni machine ni clips, et il n’est pas dans la grille du morceau.

Touchez **+ groupe** dans le mixage pour en ajouter un (jusqu’à quatre).
Touchez le nom d’un groupe pour le renommer, et le **✕** à côté ou un maintien
sur le nom le supprime. Dès que le morceau a un groupe, chaque tranche de piste
a une rangée de plus en bas. Touchez-la pour envoyer la piste à la sortie
principale ou à un des groupes.

Une tranche de groupe a un fader, le pano, muet et solo, et liste les pistes
qui y entrent.

Tout ce qui passe par un groupe traverse ses deux effets (**fx1**, **fx2**) et
son fader avant la sortie principale, donc vous pouvez mettre un seul
compresseur sur toute la batterie ou baisser toute une section d’un seul fader.

- Les **envois** d’une piste vont encore directement aux effets d’envoi, sans
  passer par le groupe.
- Mettez un groupe en **solo** pour l’entendre au complet. Mettez en solo une
  piste dans un groupe pour n’entendre qu’elle, toujours à travers les effets du
  groupe.
- Supprimer un groupe renvoie ses pistes à la sortie principale.
- Quand vous exportez **par piste**, chaque groupe est un fichier avec ses
  pistes dedans, et ces pistes n’ont pas leur propre fichier.

## Entrée latérale

Le compresseur, la porte et le filtre peuvent suivre une autre piste au lieu de
leur propre entrée. Réglez **entrée lat.** dans l’effet sur la piste voulue.
L’usage classique est un compresseur sur la basse déclenché par la grosse
caisse, pour que la basse baisse à chaque coup de grosse caisse.

L’entrée latérale entend l’autre piste avant son fader et son muet, donc baisser
la grosse caisse n’affaiblit pas l’effet, et une grosse caisse en muet sert
encore de déclencheur.

## Les deux envois

Ils commencent avec une reverb et un delay, mais chacun peut tenir n’importe
quel effet. Les boutons d’envoi sur la tranche principale montrent ce qu’ils
contiennent. Touchez pour en activer ou désactiver un, et maintenez pour choisir
et régler l’effet. Les curseurs d’envoi de chaque piste portent le nom de ce
qui est sur les envois.

Un envoi est toujours 100 % traité, puisque le son direct est déjà dans le
mixage, donc l’éditeur d’un envoi n’a pas de réglage de mélange.

Certains effets sont amusants sur un envoi, comme un décaleur de hauteur nourri
d’un peu de plusieurs pistes, ou un bitcrusher pour une copie massacrée du
mixage placée derrière.

## Tempo et réglages du morceau

Touchez le tempo dans l’en-tête du morceau pour ouvrir les réglages du morceau.

- **bpm** : tapez un nombre ou utilisez les boutons de chaque côté. **taper**
  le règle à partir de quatre touchers.
- **mesure** : le chiffrage, de 4/4 à 7/8. Une scène peut avoir le sien.
- **swing** : le retard des contretemps. **triolet** est le shuffle classique.
  **swing sur** choisit s’il fait swinguer les doubles croches ou les croches.
- **tonalité** : la tonalité et la gamme du morceau. La grille de notes ombre
  les notes en dehors, et une nouvelle piste reçoit une gamme assortie. Ça ne
  change aucune note, ni une piste qui a sa propre gamme.
- **accordage** : comment les notes sont accordées. Maintenez-le pour la liste.

Les pages **clic** et **link** sont dans la même fenêtre.

## Accordages

Le tempérament égal est l’accordage habituel, avec des demi-tons tous de la
même taille. Les autres rendent certains intervalles plus purs et d’autres plus
rudes.

- **just** : des quintes et des tierces aussi pures que possible. Superbe dans
  la tonalité où il est accordé, et rude dans les tonalités éloignées.
- **pythagorean** : des quintes pures, des tierces brillantes.
- **meantone** : des tierces pures, des quintes un peu étroites.
- **werckmeister** : un tempérament inégal où chaque tonalité marche et a sa
  propre couleur.
- **19 equal** et **24 equal** : plus de douze notes par octave. L’octave est
  19 ou 24 touches plus haut, donc le clavier joue de petits pas. 24 donne des
  quarts de ton.

Un accordage se compte à partir de la **tonique** du morceau. La note de la
tonique au milieu du clavier garde sa hauteur habituelle, donc un accordage en
la laisse le la à 440 et le reste bouge autour. Changez la tonique et
l’accordage suit.

Une piste peut avoir son propre accordage dans ses réglages (maintenez le nom de
la piste), où **morceau** veut dire qu’elle utilise celui du morceau. Les boîtes
à rythmes jouent toujours en tempérament égal.

Ajoutez vos propres accordages en fichiers Scala (.scl) avec **Importer…** dans
le menu fichier et ils rejoignent la liste. L’accordage est enregistré dans le
morceau, donc il joue pareil sur un téléphone qui n’a jamais vu le fichier.

## Comment marche le swing

Le swing retarde les contretemps sans changer l’ordre des notes, donc une
partie jouée librement swingue avec tout le reste.

Une piste peut avoir son propre swing, comme une batterie swinguée sur une
basse droite. Réglez-le dans les réglages de la piste en maintenant son nom.

Ce que vous enregistrez est gardé droit. Quand vous jouez sur un morceau
swingué, le swing est retiré avant que les notes soient écrites, donc la grille
montre où vous vouliez mettre les notes, et baisser le swing plus tard laisse
la partie droite.

Le clic ne swingue jamais.
