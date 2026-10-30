AUTOMINER v0.2 — Minecraft Java 26.1.2 (Fabric)
===============================================

Projet SOURCE (pas un .jar). Compilation : JDK 25 + Gradle 9.x, puis `gradle build`.
Le jar sort dans build/libs/autominer-0.2.0.jar. Prérequis en jeu : Fabric Loader 0.19.x
et Fabric API 0.155.3+26.1.2.

TOUCHES (modifiables dans Options > Contrôles > AutoMiner)
  G : enregistre un point (le 1er appui démarre l'enregistrement)
  H : type du PROCHAIN segment : MINAGE / TRANSIT
  J : démarre / arrête le circuit (boucle)
  K : efface le circuit

ENREGISTRER UN CIRCUIT
  1. Place-toi au départ, appuie sur G.
  2. Appuie sur H pour passer en MINAGE, marche jusqu'au bout de la ligne, G.
  3. H pour repasser en TRANSIT, parcours le couloir (un G par virage / changement de niveau), etc.
  4. Reviens au départ, puis J. Le segment de fermeture est en TRANSIT.
  L'inclinaison du viseur est enregistrée avec chaque point : vise le bloc central quand tu appuies sur G.

NOUVEAUTES PAR RAPPORT A LA v0.1
  - Circuit sauvegardé dans config/autominer-route.json (il survit au redémarrage).
  - Rotation fluide, inclinaison du viseur rejouée point par point.
  - Saut automatique en TRANSIT (marches, petits obstacles).
  - Reprise au point le plus proche après un arrêt.
  - Arrêts de sécurité : vie < 3 coeurs, outil presque cassé, inventaire plein,
    lave devant, joueur bloqué 15 s, joueur mort. Écran ouvert = pause.
  - Les touches ne sont plus relâchées en permanence hors circuit (la v0.1 gênait la marche normale).
  - Touches G/H/J/K : plus de conflit avec F5 (changement de caméra de Minecraft).

LIMITES
  Pas de pathfinding : points rapprochés dans les virages, escaliers et glace.
  Démarrage du circuit réservé au solo (constante SINGLEPLAYER_ONLY dans AutoMinerClient.java).
