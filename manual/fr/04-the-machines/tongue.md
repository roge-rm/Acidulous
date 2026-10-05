# Tongue

> Une guimbarde modélisée : une lame qui vibre dans une fente, et une bouche qui
> en fait ressortir les harmoniques.

Tongue ne joue pas d’enregistrements. Une lame d’acier est pincée et passe et
repasse dans une fente de son cadre, et chaque passage pousse une bouffée d’air.
C’est le bourdonnement, avec toutes ses harmoniques à peu près au même niveau.
Votre bouche est devant, et en changeant de forme elle fait ressortir une
harmonique puis une autre : c’est la mélodie, par-dessus un bourdon qui reste
sur la note jouée.

Il y a dix genres de guimbarde, et celles en métal ont été ajustées sur des
enregistrements de vraies guimbardes. Une guimbarde peut avoir jusqu’à cinq
lames, accordées en accord.

La molette de modulation bouge la bouche : vous pouvez jouer le bourdon d’une
main et la mélodie de l’autre. Ou mettez **jeu** sur **bouche**, et les touches
jouent la mélodie comme le fait un joueur, par-dessus un bourdon qui ne bouge
pas. La pression, c’est le souffle.

## La guimbarde

- **modèle** - le genre de guimbarde :
  - **acier** - la guimbarde d’acier courante, qui résonne longtemps.
  - **munnharpe** - une guimbarde norvégienne grave, douce et ronde.
  - **khomus** - une guimbarde iakoute, plus sombre dans l’aigu que l’acier,
    où l’on souffle souvent en jouant.
  - **morsing** - une grosse guimbarde du sud de l’Inde : grave, sombre et
    courte, pour le rythme.
  - **temir komuz** - une guimbarde kirghize : ajustée serré, brillante et
    sonnante.
  - **laiton** - une fine guimbarde de laiton découpée dans son propre cadre :
    aiguë, bourdonnante et discrète, avec le cadre qui résonne aussi.
  - **bambou** - une lame taillée dans une lamelle de bambou : douce, aérée et
    courte, avec un toc boisé.
  - **mukkuri** - une guimbarde de bambou qu’on joue en tirant une ficelle
    attachée à son cadre.
  - **genggong** - une guimbarde de palmier, elle aussi à ficelle : un coup
    sec et un bourdonnement.
  - **kouxian** - une guimbarde à plusieurs lames, trois accordées sur un
    accord pentatonique, à moins que vous régliez vous-même **anches** et
    **accord**.
- **accordage** - en cents.
- **assise** - où la lame repose dans la fente. Au milieu, les bouffées sont
  régulières et les harmoniques impaires plus fortes. Décalée d’un côté, les
  paires montent avec elles.
- **ajust.** - à quel point la lame est ajustée à la fente. Serré donne des
  bouffées plus nettes et plus d’harmoniques aiguës. Lâche est plus doux et
  plus creux.
- **anneau** - combien de temps résonne une guimbarde d’acier, en secondes. Les
  autres genres résonnent plus ou moins longtemps.

**assise**, **ajust.** et **pincer** partent du réglage propre à chaque genre :
un morsing avec ces boutons à leur valeur par défaut est déjà un morsing.

## Le pincement

- **pincer** - la netteté de la chiquenaude. Monté, c’est une chiquenaude vive
  avec un clic, baissé, une poussée douce du bout du doigt.
- **harmoniques** - la part du pincement qui va dans les harmoniques propres à
  la lame, le tintement métallique du début.
- **vélocité** - à quel point jouer plus fort change le niveau.

Pincez une touche qui résonne encore et le doigt attrape d’abord la lame, qui
ne devient donc pas de plus en plus forte. Les genres à ficelle sont plutôt
tirés puis relâchés, et **pincer** règle à quelle vitesse.

## Les lames

- **anches** - combien de lames a la guimbarde, ou **auto** pour le nombre
  propre au genre.
- **accord** - leur accord par rapport à la note jouée : **unisson** (quelques
  cents d’écart), **octaves**, **quintes**, **majeur**, **mineur** ou
  **pentatonique**, ou **auto** pour celui du genre.
- **gratter** - le temps entre une lame et la suivante, en ms. À 0, elles sont
  pincées ensemble.
- **ordre** - vers le **haut** ou le **bas** de l’accord, **épars** dans un
  ordre différent à chaque fois, ou **tour à tour** : chaque note ne pince que
  la lame suivante, et les autres continuent de résonner, et jouer un rythme
  sur une touche joue l’accord.

## La bouche

- **voyelle** - la forme de la bouche : oo, oh, ah, eh, ee. La molette de
  modulation s’y ajoute.
- **focus** - l’étroitesse des résonances de la bouche. Monté, chacune fait
  ressortir une seule harmonique et la mélodie devient plus claire.
- **prof.** - la part du son qui passe par la bouche.
- **porta.** - le temps que prend la bouche pour passer à une nouvelle voyelle,
  en ms.
- **paroles** - à quel point les paroles d’un clip bougent la bouche. Voyez
  plus bas.

La bouche garde le niveau stable : la bouger change la couleur, pas le volume.

## Les paroles et le suivi

Tongue peut parler. Donnez des paroles à ses notes dans le couloir **paroles**
de l’éditeur, comme pour Diction, et la bouche façonne le mot de chaque note :
la voyelle tenue tant que la note est enfoncée, les sons d’avant à l’entrée et
ceux d’après au relâchement. Un S ou un T siffle. Une guimbarde ne parle pas
très clairement, et les mots courts et ouverts, riches en voyelles, passent le
mieux.

La bouche peut aussi suivre une autre piste. Réglez **suivre** sur cette piste,
et sa mélodie amène la bouche sur l’harmonique la plus proche du bourdon, comme
le font les touches en mode bouche. Son octave n’a pas d’importance : chaque
note est ramenée dans les harmoniques que la bouche fait le mieux ressortir, de
la 3e à la 12e. Quand cette piste se tait, la bouche revient aux touches.
Tenez un bourdon avec un motif actif et la guimbarde joue la mélodie de l’autre
piste.

## Le souffle

- **souffle** - de l’air soufflé dans la guimbarde pendant que vous jouez. La
  pression s’y ajoute.
- **air** - la part de cet air que vous entendez comme un souffle sifflant.
- **maintien** - un souffle assez fort pour garder la lame en mouvement tant
  que la touche est enfoncée. Avec **souffle** et **maintien** tous deux montés,
  une note tenue ne meurt pas.

## Les touches

- **jeu** - ce que font les touches.
  - **bourdon** - chaque touche pince une guimbarde à sa propre hauteur.
  - **bouche** - une guimbarde reste sur la note de **bourdon**, et chaque
    touche amène la bouche sur l’harmonique du bourdon la plus proche : la
    mélodie est dans les harmoniques, comme sur une vraie guimbarde. Les
    touches s’alignent sur l’harmonique la plus proche, à partir de la 2e.
    Lâcher une touche revient à celle encore enfoncée.
- **bourdon** - la note du bourdon en mode bouche.
- **repincer** - en mode bouche, quand une touche pince de nouveau la
  guimbarde : à **chaque** touche, seulement la **première** une fois les
  autres relâchées, ou les touches jouées **fort** (vélocité 100 et plus).

## Le rythme

Tant qu’une touche est enfoncée, la guimbarde est pincée de nouveau en rythme,
au tempo du morceau, même quand il est arrêté.

- **motif** - **non**, **croches**, **doubles croches**, **galop** (long,
  court, court), **triolets**, **traits** (des traits rapides de quatre et un
  silence, comme on tire un genggong) ou **groupes** (des doubles croches en
  groupes de quatre et de trois, comme sur un morsing).
- **accent** - à quel point les pas faibles sont plus doux.
- **répétition** - à quelle fréquence un pas devient deux ou trois pincements
  rapides.

En mode bouche, le motif pince le bourdon pendant que les touches jouent la
mélodie.

## La sortie

- **arrêt** - lâcher une touche pose un doigt sur la lame. Baissé, elle
  continue de résonner. Monté, elle s’arrête vite.
- **voix** - une guimbarde, ou jusqu’à quatre. Avec une seule, une nouvelle
  note repince la même guimbarde à la nouvelle hauteur.
- **octave**, **bend** - l’octave, et l’étendue du pitch-bend en demi-tons.
- **volume**.

## Astuces

- Commencez par le **modèle**. Les genres diffèrent plus que n’importe quel
  bouton ne les change.
- La mélodie est dans la bouche. Laissez **jeu** sur **bourdon**, tenez une
  note et bougez la molette de modulation, ou mettez **jeu** sur **bouche** et
  jouez la mélodie au clavier.
- Un **motif** sur une note tenue, c’est l’essentiel du jeu de guimbarde.
  Essayez **galop**.
- Les bourdons graves parlent le mieux : vers D3 et plus bas, les harmoniques
  que choisit la bouche sont proches et la mélodie passe clairement.
- Pour un son qui ne s’arrête jamais, montez **souffle** et **maintien**.
