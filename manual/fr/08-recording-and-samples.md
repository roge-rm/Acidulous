# Enregistrement et échantillons
## La fenêtre d’enregistrement

Une seule fenêtre s’occupe de l’enregistrement, et elle s’ouvre partout où une
machine a besoin d’audio : un pad de Forage, une boucle pour Dice, un tampon
pour Pollen ou une prise pour Molt.

- **enregistrer** - choisissez l’entrée, surveillez le niveau et enregistrez.
  - **source** est **entrée** pour le microphone ou ce qui est branché, ou
    **rééchant.** pour enregistrer ce que joue l’appli.
  - **micro** est **brut** pour un instrument, ou **propre** pour une voix, avec réduction du bruit et contrôle du niveau.
  - L’accordeur dans la carte d’entrée montre la note la plus proche et de
    combien de cents vous êtes à côté, et devient vert à moins de quatre cents.
    Il écoute avant les effets d’entrée et n’affiche rien s’il n’est pas sûr de
    la note.
  - **gravé dans la prise** contient deux effets enregistrés dans le fichier,
    comme un ampli de guitare. Les effets d’une piste, eux, peuvent être changés
    n’importe quand.
- **modifier** - réécoutez la prise, coupez les bouts, réglez le niveau et
  coupez le grondement grave. **norm** et **inv** en haut normalisent et
  inversent tout le fichier. Les changements s’affichent et jouent tout de
  suite. **appliquer** les écrit dans le fichier, et **revenir** les annule
  tous.
- **bibliothèque** - tout ce que vous avez enregistré ou importé.
- **voix** - enregistre votre voix pour Diction, une courte consigne à la fois.
  - **nouvelle voix** en commence une, et **supprimer** supprime celle choisie,
    avec ses prises. **partager** l’envoie en zip, avec toutes ses prises, et
    **importer…** fait entrer une voix que quelqu’un a partagée. **note** est la
    note sur laquelle chaque consigne est chantée; choisissez-en une facile pour
    vous, car elle ne peut plus changer après la première prise.
  - Touchez **chanter** et écoutez la note. Il compte 3, 2, 1, puis la consigne
    devient rouge : chantez-la sur la note, dans l’octave qui convient à votre
    voix. La barre en dessous montre où va chaque partie. **ah-sah**, c’est ah,
    puis sah, d’un seul souffle, avec le s là où la barre le marque : c’est la
    consonne qui entre dans une voyelle et en sort qu’on enregistre. Une voyelle
    qui bouge, comme **eye**, tient sa première voyelle et passe à la deuxième à
    la marque près de la fin : aaah-ee. Il passe tout seul à la consigne
    suivante.
  - Chaque prise est vérifiée dès qu’elle est chantée. Une prise à rechanter
    reste à l’écran en rose et dit pourquoi, comme **trop fort** ou **aucune
    consonne entendue**. Les comptes ne comptent que les prises correctes.
  - **micro** montre le niveau. Chantez assez fort pour le garder bien haut dans
    la barre. Une prise enregistre toujours le micro brut, sans les effets
    gravés dans la prise.
  - **◀** et **▶** reculent et avancent, **jouer** fait réécouter une prise, et
    **encore** l’enregistre de nouveau.
  - **couper…** montre la prise avec la partie où elle a été coupée : la voyelle
    tenue, le mouvement d’une voyelle, ou la consonne. Glissez une marque pour la
    déplacer, et **partie** joue ce que les marques contiennent. **garder**
    l’enregistre, et une prise qui demandait d’être rechantée est utilisée comme
    vous l’avez marquée. **coupe auto** remet la coupe d’origine.
  - **à chanter** compte ce dont une voix a besoin : chaque voyelle, et chaque
    consonne entre des ah. Ensuite viennent les consonnes **entre les ee** et
    **entre les oo**, que vous pouvez laisser de côté. La fenêtre **depuis…** de
    Diction choisit entre elles pour chaque consonne. Une voix est enregistrée
    au fur et à mesure, donc vous pouvez arrêter et la finir un autre jour.

Le résultat est un fichier, donc le même enregistrement peut servir à plus d’une
machine.

## Importer

Vous pouvez importer du WAV, de l’AIFF, du FLAC et du MP3. Tout est converti en
WAV une seule fois à l’entrée, pour que les morceaux se chargent vite.

## Enregistrer sur une piste

**Bias** est la piste audio. Ouvrez une cellule Bias, touchez le point rouge à
côté d’un couloir, armez l’enregistrement dans le transport et appuyez sur
jouer. Le morceau joue pendant que vous enregistrez, et les autres couloirs
continuent de jouer.

Quand vous arrêtez, la prise est coupée aux limites des scènes. Un
enregistrement sur tout le morceau devient une cellule par scène, toutes
pointant vers le même fichier, avec de nouvelles cellules dans les scènes où la
piste était vide. L’enregistrement complet reste aussi dans la bibliothèque de
sons, donc vous pouvez annuler le découpage et le placer à la main.

La coupe, les fondus, les fondus enchaînés, l’aplatissement des couloirs et le
suivi du tempo sont sur la page de **Bias**. Aucun d’eux ne change le fichier.

Les prises peuvent durer jusqu’à une demi-heure. Tout ce qui dépasse deux
minutes est converti une fois en arrière-plan et lu en continu depuis le
stockage, donc il peut y avoir une courte attente la première fois qu’une
longue prise sert.

Un seul couloir enregistre à la fois, et armer un deuxième couloir désarme le
premier.

Si l’enregistreur a pris du retard et laissé un trou dans la prise, elle n’est
pas découpée, et toute la prise reste dans la bibliothèque. Si le transport n’a
jamais joué, rien n’a été enregistré sur une scène, et l’appli vous le dit.

## Les machines qui utilisent l’audio

- **Forage** - un échantillon par pad, avec un filtre, un crusher et une
  enveloppe de hauteur.
- **Mosaic** - des zones sur le clavier et la vélocité, à partir de vos propres
  fichiers ou d’une SoundFont.
- **Pollen** - des grains tirés d’un fichier ou de l’entrée en direct.
- **Dice** - une boucle coupée en tranches.
- **Molt** - une prise chantée, accordée par la grille de notes.
- **Cipher** et **Filament** peuvent utiliser l’entrée en direct.
- **Bias** - quatre couloirs d’enregistrements le long du morceau. Ses
  enregistrements appartiennent à ses cellules plutôt qu’à la machine.
