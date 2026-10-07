# Formula
> Tapez une formule et elle façonne la piste, échantillon par échantillon.

Formula emploie le même petit langage que la machine Formulate :
des nombres entiers et les opérateurs du C (`+ - * / % & | ^ << >>`,
comparaisons, `? :`) et `sin`, `abs`, `min` et `max`. La piste entre comme
`x`, de 0 à 255, le silence à 128. Ce que la formule donne, pris de 0 à 255,
ressort. Ainsi `x` seul, c’est la piste réduite à huit bits, et tout le reste
la tord à partir de là.

Touchez **modifier…** au-dessus des boutons pour en écrire une, ou choisissez
un exemple pour commencer. La formule n’est appliquée que quand vous appuyez
sur OK, et si elle ne peut pas être lue, la raison s’affiche en rouge. Sans
formule, la piste passe intacte.

## Ce qu’une formule peut lire

- **x** : la piste, de 0 à 255.
- **t** : un compteur qui monte à la **vitesse**.
- **a**, **b**, **c** : les trois boutons, de 0 à 255.
- **r** : un nombre aléatoire neuf, de 0 à 255, à chaque échantillon.

## Les réglages

- **a**, **b**, **c** : lus par la formule, ou ignorés si elle ne s’en sert
  pas.
- **vitesse** *(extra)* : à quelle vitesse `t` compte, de 1 à 48 kHz. À
  8 kHz, la plupart des formules écrites pour ce genre de chose sonnent comme
  prévu.
- **satur.** : pousse la piste plus fort dans la formule, jusqu’à 24 dB.
- **lisse** *(extra)* : adoucit les marches que laisse la formule. Au maximum,
  il est coupé.
- **mélange** : la piste sèche face à celle de la formule.
- **gain** : le niveau de sortie.

## Astuces

- `x & (255 << (a >> 5))` jette des bits à mesure que **a** monte.
- `t >> 11 & 1 ? x : 128` coupe la piste au rythme du compteur.
- La sortie reste toujours sous le plein niveau, mais elle peut être dure :
  commencez avec **mélange** bas ou **lisse** baissé.
