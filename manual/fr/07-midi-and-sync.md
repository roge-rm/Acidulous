# MIDI et jeu avec d’autres
Vous pouvez utiliser le MIDI avec toutes sortes de claviers et de contrôleurs, y compris MPE.
L’Intuitive Instruments Exquis et le Novation Launchpad Pro MK3 ont un support
particulier (je les ai moi-même).

## Jouer d’un clavier

Les claviers MIDI USB et Bluetooth fonctionnent tout de suite. Les notes vont à
la piste ouverte, ou à une piste que vous fixez pour qu’elle reste la même peu
importe ce que vous regardez.

Si un contrôleur joue tout trop fort ou trop doux, tournez **vélocité** dans
l’onglet **notes**. **plus doux** baisse le milieu de la plage et **plus fort**
le monte, et les notes les plus douces et les plus fortes ne bougent pas.
L’affichage à côté montre la vélocité de chaque note telle que l’appli la reçoit.

### MPE

Avec un contrôleur MPE, chaque doigt a son propre bend, sa pression et son
glissé, donc plier une note laisse les autres tranquilles. Laissez **zone** sur
**auto** et l’appli suit le contrôleur : elle prend la zone et la plage de bend
qu’il annonce, ou les devine dès que deux doigts sont posés sur des canaux
séparés. La carte dit ce qu’elle a trouvé. Choisissez **basse** ou **haute**
pour régler la zone et la plage de bend à la main.

Ce que fait chaque geste :

- **Bend** change la hauteur de la note sur toutes les machines mélodiques sauf
  Reflux, l’orgue et Diction, qui plient en bloc. Sur un harmonica Draw joué
  comme par un joueur, plier vers le bas plie avec la langue, aussi loin que le
  trou le permet.
- **Pression** ouvre le son et le rend plus fort. Sur Brazen, Timber, Draw et
  Tongue, c’est le souffle, et sur Filament elle appuie aussi sur l’archet.
  Hammer n’en tient pas compte : un piano n’a plus rien à presser une fois la
  corde frappée. **pression** sur le panneau d’une machine règle la quantité.
- **Glissé** (CC 74) rend la note plus brillante. Il ouvre le filtre sur
  Trinity, Ratio et Mosaic, monte le point de pincement sur la corde de
  Filament, serre les lèvres ou l’anche sur Brazen et Timber, rapproche les
  anches de Draw de leurs fentes, serre l’anche de Tongue dans son cadre et
  déplace le morph de Cumulus. **glissé** sur le panneau règle la quantité.
  Dans Nexus, le module **touch** donne à chaque voix la pression et le glissé
  de son doigt, à brancher où vous voulez.

Un clavier qui envoie de l’aftertouch polyphonique presse aussi chaque note
séparément, sans zone. Les autres contrôleurs sur le canal d’un doigt, comme la
molette de modulation ou la pédale de maintien, agissent sur toute la piste
comme d’habitude.

Le bend, la pression et le glissé de chaque note sont enregistrés avec les notes.

### Exquis et Launchpad Pro

L’Exquis et le Launchpad Pro ont leurs propres pages :
[Exquis](07-midi-and-sync/exquis.md) et [Launchpad Pro](07-midi-and-sync/launchpad.md).

### Pédales

Une pédale branchée à votre clavier fonctionne sur toutes les machines
mélodiques :

- **Maintien** (la pédale de droite) fait durer les notes après que vous
  lâchez les touches, jusqu’à ce que vous la releviez. Sur Filament, elle lève
  aussi les étouffoirs, donc les cordes que vous ne jouez pas résonnent avec.
- **Sostenuto** (celle du milieu) tient seulement les touches enfoncées au
  moment où vous l’avez pressée, et les notes jouées ensuite s’arrêtent comme
  d’habitude.
- **Douce** (celle de gauche) joue les notes plus doucement tant qu’elle est
  enfoncée.

Hammer prend une pédale enfoncée à moitié, comme un piano : une pédale de
maintien à mi-course laisse les étouffoirs juste toucher les cordes. Toutes les
autres machines entendent une pédale comme relevée ou enfoncée, et la mi-course
compte comme enfoncée. La sortie MIDI envoie la pédale aussi loin qu’elle est
enfoncée.

Les pédales sont enregistrées comme couloirs dans la bande d’automatisation, un
chacune, et vous pouvez aussi les y dessiner à la main. Sur Hammer, un couloir
garde jusqu’où la pédale était enfoncée et où elle a bougé; sur tout le reste,
c’est relevée ou enfoncée. Les boîtes à rythmes n’en tiennent pas compte.

## Affecter un contrôleur

Maintenez rétablir pour entrer en mode affectation. Les commandes qu’on peut
affecter sont mises en surbrillance. Touchez-en une, puis tournez un bouton ou
appuyez sur une touche de votre contrôleur pour les relier. Les boutons, les
curseurs, les commandes de mixage et les boutons de transport peuvent tous être
affectés à un CC ou à une note.

Un bouton affecté s’enregistre dans l’automatisation comme si vous le tourniez
à la main. Les boutons affectés comme jouer, arrêter et fill ne s’enregistrent
pas.

## Horloge

L’appli peut envoyer l’horloge MIDI (avec start, stop et song position) à du
matériel, et suivre une horloge entrante en prenant son tempo de l’autre
appareil.

**suivre** dans l’onglet **contrôle** de la fenêtre MIDI a trois réglages :

- **oui** suit toujours, même si rien n’arrive.
- **auto** suit une horloge quand elle arrive et revient au tempo du morceau
  une seconde après qu’elle s’arrête.
- **non** ignore l’horloge entrante.

**envoyer**, à côté, active et désactive l’envoi de l’horloge. L’envoi des notes
d’une piste se règle piste par piste, dans ses réglages ou sur sa tranche de
mixage.

## Link

Ableton Link partage le tempo et la position dans la mesure avec les autres
apps Link du même réseau, dans les deux sens. Activez-le et le nombre d’apps
connectées s’affiche. Il garde le premier temps de tout le monde ensemble sans
que personne soit le chef.
