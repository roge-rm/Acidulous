# Formulate

> Un synthé 8 bits, et une forme d’onde que vous tapez comme une formule.

Formulate est un synthé de puce sonore avec impulsion, triangle et bruit, comme
les vieilles consoles de jeu. Il peut aussi jouer une forme d’onde que vous
écrivez comme une formule.

## La puce

- **onde** - impulsion, triangle, bruit ou la formule.
- **rapport** - la largeur d’impulsion. **pwm** et sa **vitesse** la balaient.
- **sous-oct.** - ajoute une octave en dessous.
- **bruit** - fait passer le bruit d’un souffle à un bourdonnement métallique.
- **bits**, **fréq. éch.** et **lisse** - la résolution, la fréquence
  d’échantillonnage et à quel point les marches sont adoucies.

## Tables de tracker

Il y en a trois, **arpège**, **rapport cyclique** et **volume**, tapées en
texte et parcourues à chaque tick comme un instrument de tracker. Une table
d’arpège `0 4 7` jouée vite fait un accord avec une seule voix.

## La formule

**formule** est une expression où **x** va de 0 à 1 sur un cycle de l’onde.
`sin(x*2*pi)` est un sinus, `x*2-1` est une dent de scie et `(x<0.3)?1:-1` est
une impulsion à 30 % de rapport cyclique.

**contre x** choisit si la formule est calculée une fois par cycle ou à chaque
échantillon. Les trois boutons macro **a**, **b** et **c** peuvent être
utilisés par leur nom dans la formule, alors vous pouvez lui donner des
commandes. Si une formule a une erreur, le panneau vous le dit.

## Astuces

- Utilisez une macro dans la formule et automatisez-la. `sin(x*2*pi*a)` avec
  **a** sur un couloir balaie le timbre.
- Les tables de tracker changent beaucoup de choses. Un son simple avec une
  bonne table de volume bat souvent une formule astucieuse sans table.
