# Harmonizer
> Ajoute deux voix à des degrés de la gamme, pour qu’elles restent dans la tonalité.

Un décaleur de hauteur qui connaît la tonalité. Vous réglez les intervalles en
degrés de la gamme plutôt qu’en demi-tons, donc une tierce sort majeure ou
mineure selon la note, comme une vraie partie d’harmonie.

## Les réglages

- **intervalle** et **intervalle2** : les deux voix ajoutées, de -7 à +7
  degrés.
- **gamme** *(extra)* : la gamme où les degrés sont comptés, dans la même liste
  que le modificateur Scale.
- **tonalité** *(extra)* : la tonalité.
- **fenêtre** : de 10 à 120 ms. Court suit les parties rapides mais sonne plus
  granuleux, et long est plus doux mais brouille les attaques.
- **réinjection** : renvoie la sortie dedans, en empilant l’intervalle sur
  lui-même.
- **mélange** : le son traité contre le son direct.

## Astuces

- Réglez la tonalité, sinon ce n’est qu’un décaleur de hauteur et certaines
  tierces seront fausses.
- Sur la batterie ou tout ce qui est percussif, prenez une fenêtre courte
  (vers 20 ms).
- Une quinte ou une octave en dessous est souvent plus utile qu’une tierce au
  dessus, et trahit moins les défauts de justesse.
- Deux degrés avec de la réinjection empilent un accord.
