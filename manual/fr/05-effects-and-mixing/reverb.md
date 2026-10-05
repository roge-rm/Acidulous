# Reverb
> Une salle qui peut aussi se figer, se couper, miroiter ou s’écraser.

Une réverbération avec la taille, l’amortissement et le prédélai habituels,
plus quelques extras.

## Les réglages

- **taille** : la grandeur de l’espace.
- **amortir** : à quel point la salle absorbe. Amortie, elle sonne comme du
  tapis, et sans amortissement, comme de la céramique.
- **ton** : un passe-bas sur la queue de réverbération.
- **prédélai** : un écart avant que la réverbération commence, jusqu’à 200 ms.
- **mélange** : le son traité contre le son direct.
- **figer** *(extra)* : tient la queue pour toujours.
- **gate** *(extra)* : coupe la queue net, pour la caisse claire à porte
  classique des années 80.
- **miroitement** *(extra)* : renvoie la queue une octave plus haut, donc un
  accord tenu continue de monter.
- **bits** et **écraser** *(extra)* : réduction de bits et de fréquence
  d’échantillonnage sur la queue seulement.
- **oscillation** *(extra)* : fait dériver un peu la queue pour qu’elle ne
  soit jamais tout à fait immobile.

## Astuces

- Si la réverbération rend le son brouillé, essayez 30-60 ms de **prédélai**
  avant de la rapetisser.
- Le miroitement marche mieux avec un mélange bas et une longue queue.
- Mettez **bits** et **écraser** sur un envoi pour une copie lo-fi du mixage
  derrière la version propre.
