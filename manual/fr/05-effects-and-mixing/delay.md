# Delay
> Des échos sur une valeur de note, en quatre modes, qui peuvent s’atténuer pendant que vous jouez.

Un delay calé sur le morceau. **temps** est une valeur de note, donc un delay
à la croche pointée reste à la croche pointée quand le tempo change.

## Les réglages

- **temps** : l’écart entre les échos, de 1/32 à une mesure entière, avec les
  valeurs pointées.
- **réinjection** : la part de chaque écho renvoyée dedans, jusqu’à 0,95.
- **ton** : un passe-bas sur les échos, donc chaque répétition est plus sourde
  que la précédente.
- **mode** : comment les échos reviennent :
  - **normal** : un écho de chaque côté.
  - **ping-pong** : les échos rebondissent entre la gauche et la droite.
  - **multi-têtes** : deux à quatre échos dans le temps, répartis de gauche
    à droite. Seul le dernier est réinjecté.
  - **inverser** : chaque tranche de **temps** est jouée à l’envers. Avec de
    la réinjection, le passage suivant repart à l’endroit.
- **têtes** : combien d’échos le mode multi-têtes place dans le temps, de 2
  à 4.
- **écart** : où tombent les têtes : au milieu elles sont régulières, plus bas
  elles se serrent au début et plus haut vers la fin du temps.
- **mélange** : la part d’écho que vous entendez.
- **atténuer** *(extra)* : baisse les échos pendant que le son direct joue et
  les ramène dans les silences.
- **oscillation** *(extra)* : fait dériver lentement le temps du delay comme
  un magnétophone. Un peu le réchauffe, et plus désaccorde les échos.

## Astuces

- Descendez **ton** à quelques kHz pour qu’une longue réinjection se fonde dans
  le fond au lieu de s’empiler.
- Utilisez **atténuer** au lieu de baisser le mélange pour que le delay
  n’écrase pas une partie.
- À l’envers sur une noire ou une blanche, le delay monte vers chaque temps.
  Une tranche inversée part sur le temps du morceau, peu importe où la lecture
  commence. Une tranche de plus de 3 secondes (une mesure sous 80) est jouée
  par moitiés.
- Trois têtes régulières dans une noire font un triolet; quatre têtes sur une
  blanche répètent chaque croche.
- Sur un envoi, le delay est toujours 100 % traité, et le curseur d’envoi de
  chaque piste règle combien lui arrive.
