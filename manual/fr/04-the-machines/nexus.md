# Nexus

> Un synthé modulaire dont les modules sont les autres machines.

Nexus vous laisse construire votre propre machine. Ses modules sont les
instruments de l’application, et vous pouvez relier un filtre de Reflux, un
oscillateur de Trinity et une banque de modes de Resonance.

## Le patch est du texte

Un patch Nexus est une liste de modules et de connexions écrite en texte, que
vous pouvez lire, copier et envoyer à quelqu’un. Il a son propre écran, car un
patch demande de la place.

Ses boutons sont des paramètres normaux, et tout ce qui est dans un patch peut
être automatisé, assigné à un contrôleur et enregistré.

**cadrer** ajuste le patch à l’écran. Il place les modules dans l’ordre où le
son les traverse, de gauche à droite, sur autant de rangées qu’il convient à
l’écran, puis zoome pour tout montrer. Tournez le téléphone et touchez-le de
nouveau pour une disposition adaptée à ce sens. C’est une seule étape
d’annulation, et les modules peuvent revenir où ils étaient.

## Les modules sur le canevas

Chaque module est un panneau sur des rails, aussi large que ses boutons et ses
prises le demandent. Les entrées sont les prises turquoise et les sorties les
prises ambre, dans la boîte sombre du bas. La couleur en haut dit de quel genre
de module il s’agit, et le voyant à côté du nom montre à quel point il
travaille. Les câbles prennent la couleur du module d’où ils viennent.

Tournez un bouton sur un panneau en glissant vers le haut ou le bas. Zoomez
pour des réglages plus fins, et touchez-le deux fois pour le remettre. Les
mêmes boutons sont sous le canevas quand le module est sélectionné. En mode
d’assignation, touchez un bouton sur un panneau pour l’assigner et maintenez-le
pour effacer l’assignation.

## Les effets en modules

Les effets d’insertion sont aussi des modules, et un effet peut aller
n’importe où dans un patch et être bougé par un câble : **reverb**, **chorus**,
**phaser**, **crush**, **shift**, **drive** et **swell**. Chacun sonne
exactement comme sur une piste. Leur deuxième entrée bouge le bouton dont elle
porte le nom, comme **size** sur la réverbération ou **amount** sur swell. Chacun
a une fraction de milliseconde de retard, qu’on n’entend pas.

## Les instruments des autres machines

D’autres machines sont des modules, chacun avec la partie qui fait son son :

- **bore** : le cuivre de Brazen, des lèvres sur un tube avec un pavillon.
- **pipe** : le tuyau de Timber, avec une anche, une anche double ou le jet
  d’air d’une flûte.
- **reed** : l’anche libre de Draw : harmonica, accordéon, mélodica, harmonium
  ou concertina.
- **jaw** : l’anche de la guimbarde de Tongue, pincée par son entrée **trig**.
  Passez-la dans un **throat** et bougez la voyelle pour le son de guimbarde.
- **piano** : tout Hammer, joué par une **pitch** et un **gate**.
- **guitar**, **mallets**, **sitar**, **drum**, **pipes**, **bird** et
  **water** : tout Fret, Tine, Sympath, Palm, Chanter, Aviary et Fathom, joués
  de la même façon, chacun avec huit de ses propres boutons. L’oiseau, les
  cornemuses, le tanpura et l’eau continuent tant que le gate est tenu.
- **throat** : le conduit vocal de Diction en filtre : tout ce qui le traverse
  devient une voyelle, de ou à i.
- **formula** : les expressions de Formulate, en oscillateur ou pour façonner
  ce qui arrive dans **x**. Sélectionnez-le et touchez **modifier…** pour taper
  la formule.
- **follow** : l’oreille de Molt : la hauteur de ce qui entre, un gate tant
  qu’il en est sûr, et son niveau. Chantez dedans pour jouer le patch.

Les instruments à vent (**bore**, **pipe** et **reed**) prennent leur air à
l’entrée **breath**, depuis une enveloppe ou une source de pression. Sans rien
dedans, ce sont les touches qui soufflent.

## Les macros

Huit macros, **1** à **8** sous **macros**, plus **morph**. Le patch décide de
ce qu’elles contrôlent, et un gros patch peut se jouer avec quelques boutons.

## MPE

Le module **touch** donne à chaque voix la pression et le glissé du doigt qui la
joue, depuis un contrôleur MPE ou un clavier à aftertouch polyphonique. Reliez
ses sorties **prs** et **slide** à n’importe quoi, comme la coupure d’un filtre
ou un VCA.

## L’entrée audio

Il y a un module d’entrée audio, et tout ce qui entre dans le téléphone peut
passer par un patch.

## Astuces

- Gardez les patchs petits et nommez vos macros.
- Reliez **morph** aux deux ou trois choses qui changent le plus le patch.
- Nexus n’est pas l’endroit le plus facile pour commencer. Les autres machines
  font la plupart des choses plus directement, et Nexus sert aux combinaisons
  qu’elles ne couvrent pas.
