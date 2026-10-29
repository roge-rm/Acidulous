# Reverb
> Douze salles et choses qui résonnent, avec une queue à atténuer, éloigner ou figer.

Une réverbération en douze modèles. **modèle** en choisit un, et le panneau
montre les boutons de tous les modèles plus ceux du modèle choisi.

## Les modèles

- **salle** : la réverbération d’origine, une salle simple.
- **plaque** : une plaque de métal, brillante et dense.
- **hall** : une grande salle avec des premières réflexions, les graves durant
  plus longtemps que les aigus.
- **ressort** : la cuve à ressorts d’un ampli de guitare. Chaque écho revient
  en gazouillis.
  - **goutte** : jusqu’où chaque écho gazouille.
  - **coup** : à quel point un coup sec secoue la cuve, jusqu’au boing.
- **ancien** : une des premières réverbérations numériques, des lignes
  courtes à basse fréquence d’échantillonnage, granuleuse et métallique.
  - **vitesse** : la fréquence de la cuve, de 8 à 32 kHz. Plus bas, c’est
    plus sombre et plus granuleux.
- **espace** : une salle bâtie à partir de ses mesures en mètres. Elle n’a
  pas de bouton **taille**.
  - **largeur**, **prof.** et **hauteur** : la salle, de 2 m jusqu’à 30, 50
    et 15 m.
  - **position** : où vous êtes, de l’avant à l’arrière.
  - **matériau** : les murs, en carreau, plâtre, bois ou rideaux.
- **tube** : un tuyau accordé dans lequel la piste résonne, fermé à un bout.
  - **hauteur** : en demi-tons à partir du do central.
  - **suivre** : s’accorde plutôt sur la dernière note de la piste. Seulement
    sur les insertions d’une piste, car les groupes, les envois, le master et
    l’entrée ne reçoivent pas de notes.
  - **ouvert** : d’un tuyau fermé à un bout (harmoniques impaires) à un tuyau
    ouvert aux deux bouts (toutes les harmoniques).
- **corde** : une corde accordée dans laquelle la piste résonne, avec
  **hauteur** et **suivre** comme sur le tube.
  - **raideur** : tire les harmoniques vers le haut, d’une corde à un fil de
    métal.
  - **médiator** : où le son tombe sur la corde, du bout au milieu.
- **peau** : une peau de tambour que la piste frappe. **taille** est le
  tambour.
  - **tension** : accorde la peau.
  - **timbre** : des fils sous la peau qui grésillent quand un coup la fait
    assez bouger, donc une grosse caisse les déclenche et une note douce non.
- **tonnerre** : une feuille de métal. Un coup doux bourdonne et un coup fort
  s’ouvre en fracas après la frappe.
  - **métal** : à quel point elle passe au fracas, d’un gong lourd à une
    feuille mince.
- **larsen** : une salle avec un micro en boucle.
  - **larsen** : monté, les notes de la salle sonnent plus longtemps, puis
    chantent et tiennent, donc tout ce que vous jouez devient des bourdons.
    **taille** les réaccorde.
- **flutter** : deux murs parallèles, comme une cage d’escalier ou un tunnel.
  L’écho qui rebondit entre eux sonne comme une note.
  - **murs** : leur écart, d’un quart de mètre (une note aiguë) à 20 m (un
    flottement lent).

## Sur tous les modèles

- **taille** : la grandeur de l’espace, ou la durée de la résonance. Le modèle espace n’en a pas.
- **amortir** : à quel point la salle absorbe. Amortie, elle sonne comme du
  tapis, et sans amortissement, comme de la céramique.
- **ton** : un passe-bas sur la queue de réverbération.
- **prédélai** : un écart avant que la réverbération commence, jusqu’à 200 ms.
- **mélange** : le son traité contre le son direct.
- **atténuer** : baisse la queue pendant que la piste joue et la ramène dans
  les silences. **rel. att.** est la vitesse du retour, de 50 ms à 2 s.
- **distance** : éloigne le son, avec un prédélai plus long, des premières
  réflexions plus douces et plus tardives, plus de queue contre le son
  direct, une queue plus sourde, et un son direct plus étroit avec une queue
  plus large.
- **éclosion** : la queue commence mince et se remplit en jusqu’à deux
  secondes, puis retombe quand le son s’arrête.
- **figer** *(extra)* : tient la queue pour toujours.
- **gate** *(extra)* : coupe la queue net, pour la caisse claire à porte
  classique des années 80.
- **miroitement** *(extra)* : renvoie la queue une octave plus haut, donc un
  accord tenu continue de monter.
- **bits** et **écraser** *(extra)* : réduction de bits et de fréquence
  d’échantillonnage sur la queue seulement.
- **oscillation** *(extra)* : fait dériver un peu la queue pour qu’elle ne
  soit jamais tout à fait immobile.

## Astuces

- Si la réverbération rend le son brouillé, essayez 30-60 ms de **prédélai**
  ou un peu d’**atténuer** avant de la rapetisser.
- Le miroitement marche mieux avec un mélange bas et une longue queue.
- Mettez **bits** et **écraser** sur un envoi pour une copie lo-fi du mixage
  derrière la version propre.
- Le tube et la corde avec **suivre** résonnent avec la partie. Essayez-les
  sur des percussions avec **hauteur** réglée sur la tonalité du morceau.
