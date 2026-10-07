# Swell
> Une compression vers le haut : les passages doux montent rejoindre les
> forts, sur une bande ou trois.

## Les réglages

- **plancher** : tout ce qui est plus fort que ce niveau est remonté, de -80 à
  0 dB. Ce qui est plus de 12 dB en dessous reste exactement comme avant, donc
  le souffle et le ronflement au fond d’un enregistrement ne bougent pas.
- **plafond** : le niveau vers lequel tout ce qui dépasse le plancher est tiré,
  de -30 à 0 dB. Un son déjà au-dessus du plafond est ramené à lui.
- **intensité** : de combien. À 0 rien ne bouge, à 1 chaque son au-dessus du
  plancher arrive au plafond, et à mi-course il fait la moitié du chemin.
- **partage** *(extra)* : d’une bande (0) à trois (1), coupées à 300 Hz et
  5 kHz. Partagé, un aigu discret ou un grave maigre remonte tout seul, et
  c’est de là que viennent l’air et le souffle. Les bandes se recombinent
  exactement en l’entrée, donc le partage ne change jamais un son qui n’est
  pas remonté.
- **relâche** : la vitesse à laquelle il relâche après un moment fort, de 5 ms
  à 2 s. Lent, c’est proche d’une normalisation et la forme d’une phrase est
  gardée. Rapide, il suit chaque coup, et très rapide, il suit la forme d’onde
  elle-même et devient une distorsion.
- **mélange** : le son sec face au son remonté.
- **gain** : le niveau de sortie, pour compenser ce qu’il ajoute.

Il regarde quelques échantillons en avance, pour qu’un son fort soudain soit
déjà baissé quand il arrive. C’est 8 échantillons, moins de 0,2 ms.

## Astuces

- Partez du préréglage **Loud** sur un bus et montez **intensité** jusqu’à ce
  que les passages doux soient où vous les voulez.
- Pour la batterie, **Room** remonte tout ce qui se passe entre les coups.
  Baissez **plancher** pour remonter davantage la pièce.
- Pour un mur de son, poussez **intensité** à 1 et **relâche** sous 50 ms,
  puis baissez **mélange** jusqu’à ce que la dynamique revienne.
- Sur un envoi de réverbération ou d’écho, il remonte les queues, et une
  réverbération courte sonne comme une longue.
- Réglez **plafond** quelques dB sous celui du limiteur du master pour qu’ils
  ne se battent pas.
