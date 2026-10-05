# Compressor
> Les réglages habituels, une entrée latérale de n’importe quelle piste et un pompage calé sur le tempo.

## Les réglages

- **seuil** : le niveau à partir duquel il agit, de -60 à 0 dB.
- **ratio** : la force de la compression, de 1:1 à 20:1. Au-delà d’environ
  10:1, il agit comme un limiteur.
- **attaque** : la vitesse de réaction, de 0,1 à 100 ms. Plus lent laisse
  passer le début de chaque coup, ce qui rend la batterie plus percutante.
- **relâche** : la vitesse à laquelle il lâche, de 10 à 1000 ms.
- **compens.** : rajoute du niveau, jusqu’à 24 dB.
- **pompage** *(extra)* : baisse le son en mesure avec le morceau, comme une
  entrée latérale d’une grosse caisse qui n’est pas là.
- **vit. pompe** *(extra)* : à quelle fréquence il pompe, 1/16, 1/8, 1/4 ou
  1/2.
- **entrée lat.** : ce que le compresseur écoute, soit **propre** (cette piste),
  soit une autre piste, qui baisse celle-ci chaque fois que l’autre est forte. Il
  entend l’autre piste avant son fader et son muet, donc une grosse caisse en
  muet sert encore de déclencheur.

## Astuces

- Sur la batterie, **attaque** change le timbre. Vers 20 ms, c’est plus
  percutant, et à 1 ms, plus plat.
- Pour baisser la basse sous la grosse caisse, réglez **entrée lat.** du
  compresseur de la basse sur la batterie, un ratio de 8:1 ou plus, l’attaque
  la plus rapide et une relâche de 80-150 ms, puis baissez le seuil jusqu’à ce
  qu’il baisse de 6-10 dB à chaque coup.
- Une vraie entrée latérale suit la vraie batterie, fills compris. **pompage**
  n’a besoin d’aucun routage et ne rate jamais un temps, ce qui est pratique
  avant que la batterie soit écrite.
- Sur la sortie principale, allez-y doucement : 2:1, avec 2-3 dB de réduction
  sur les passages forts.
