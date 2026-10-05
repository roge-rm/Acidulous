# Importer et exporter
**Exporter…** dans le menu fichier fait le rendu du morceau plus vite qu’en temps
réel, par le même moteur qui le joue, queues comprises.

Vous pouvez exporter tout le morceau, la scène courante ou des pistes séparées.

## Formats

- **WAV** et **AIFF** - 16 ou 24 bits, ou 32 bits flottant.
- **FLAC** - sans perte et plus petit.
- **MP3** et **AAC** - au débit que vous choisissez.
- **MIDI** - les notes sans le son, avec la transposition et la vélocité fixe
  de chaque piste appliquées, et ses couloirs de pédale, de molette de
  modulation et de pression. Les pistes de batterie vont sur le canal 10 comme
  batterie General MIDI, pour que les autres logiciels entendent les bons sons.
- **paquet** - le morceau et les échantillons qu’il utilise, dans un seul
  fichier que vous pouvez partager.

## Pistes séparées

**par piste** écrit chaque piste dans son propre fichier en une seule passe,
avec le mixage complet. Chaque groupe du mixage est aussi une piste séparée, et
une piste dirigée vers un groupe fait partie du fichier du groupe au lieu
d’avoir le sien, donc les pistes séparées s’additionnent pour donner le mixage.

## Niveau

**niveau** est soit **tel que mixé**, soit **-14 LUFS**. Avec -14, le morceau
est rendu deux fois : une fois pour le mesurer, puis avec le gain qui l’amène à
-14 LUFS, à peu près le niveau des services de diffusion en continu. Le gain
est retenu s’il pousse la crête vraie au-dessus de -1 dBTP, donc un morceau très
dynamique peut finir un peu sous -14. Les pistes séparées reçoivent le même
gain, donc elles s’additionnent toujours pour donner le mixage.

La tranche de la sortie principale dans le mixage montre la même mesure pendant
que vous jouez : les LUFS intégrés, et en dessous la mesure à court terme
(**S**) et la crête vraie (**TP**). TP devient rouge au-dessus de -1 dBTP. Elle
se remet à zéro chaque fois que vous appuyez sur jouer, ou quand vous la
touchez.

## Les exportations se répètent

Exporter deux fois le même morceau donne des fichiers identiques, car chaque
machine est remise à zéro avant le début du rendu.

## Importer

**Importer…** dans le menu fichier prend un fichier n’importe où sur le
téléphone, et ce qui se passe dépend de ce que c’est :

- **Un fichier MIDI** ouvre une fenêtre qui montre chaque partie du fichier
  avec une machine choisie pour elle. Touchez une machine pour la changer, ou
  choisissez **ignorer** pour laisser la partie de côté. La batterie sur le
  canal 10 va à Hexbeat, chaque instrument placé sur le son correspondant. Les
  parties qui disent de quel instrument il s’agit reçoivent une machine qui
  convient, comme un orgue qui va à Manual et les cuivres à Brazen.
- Le fichier est coupé en scènes de 4, 8 ou 16 mesures. Un passage identique au
  précédent devient une répétition, donc une boucle entre comme une seule scène
  jouée plusieurs fois. Le tempo et le chiffrage viennent du fichier, et là où
  le tempo change, les scènes qui suivent ont leur propre tempo.
- Les pédales, la molette de modulation et la pression entrent comme couloirs,
  et le pitch-bend comme bends sur les notes.
- Les paroles du fichier, y compris celles d’un fichier karaoké, vont sur les
  notes où elles commencent, et cette partie va à Diction pour les chanter.
- Il s’ouvre comme un nouveau morceau et est enregistré tout de suite.
- **Un paquet** (un .zip fait par Exporter) s’ouvre comme un morceau, avec ses
  échantillons. Si vous avez déjà un échantillon du même nom, le vôtre est gardé
  et celui du paquet entre sous un nouveau nom.
- **Un son** (WAV, AIFF, FLAC ou MP3) va dans la bibliothèque de sons, jusqu’à
  dix minutes.
- **Un accordage** (un fichier Scala .scl) s’ajoute à la liste des accordages,
  sous la tonalité dans la fenêtre du tempo et dans les réglages de chaque
  piste.

Un morceau importé ne remplace jamais un morceau que vous avez enregistré. Si le
nom est pris, il reçoit un numéro à la suite.

## Partager

Quand une exportation est finie, **Partager** l’envoie tout de suite par le
menu de partage du téléphone : courriel, Drive, une conversation ou une autre
appli. Les pistes séparées partent avec tous leurs fichiers ensemble.

**Partager le morceau…** dans le menu fichier envoie le morceau ouvert en
paquet, échantillons compris, pour que quelqu’un d’autre l’ouvre dans Acidulous.

L’inverse fonctionne aussi. Ouvrez un fichier MIDI, un paquet, une voix partagée
ou un son avec Acidulous, ou partagez-le vers l’appli, et il va là où
**Importer…** l’aurait mis; une voix va à la page voix dans **Son**.
