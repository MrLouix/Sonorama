# Sonorama — Plan de codage

Ce plan traduit la [spécification](specification.md) en modules, en ordre de construction et en critères de vérification. Il décrit aussi les choix faits là où la spécification laissait une marge.

## 1. Pile technique

| Brique | Choix | Version |
| --- | --- | --- |
| Build | Gradle (wrapper), Android Gradle Plugin, catalogue `gradle/libs.versions.toml` | 9.9.1 / 9.5.1 |
| Langage | Kotlin (support intégré à AGP 9), KSP pour Room | 2.4.21 / 2.3.13 |
| Interface | Jetpack Compose, Material 3 | BOM 2026.09.00 |
| Audio | Media3 ExoPlayer + Media3 Session | 1.11.1 |
| Données | Room, DataStore Preferences, kotlinx.serialization (manifestes, export de pages) | 2.8.5 / 1.3.0 / 1.13.0 |
| SDK | `minSdk` 26, `compileSdk` et `targetSdk` 37 | |

Aucune dépendance à Google Play Services, aucune permission réseau. Pas de bibliothèque d'injection : un conteneur manuel (`AppContainer`) suffit à cette taille.

## 2. Architecture

```
io.github.mrlouix.sonorama
├── SonoramaApp / AppContainer    câblage : base, dépôt, réglages, moteur
├── audio/
│   ├── VolumeCurve               courbe perceptive 0–100 % → gain
│   ├── LoopPlayer(+Factory)      abstraction d'un lecteur en boucle (testable)
│   ├── ExoLoopPlayer             implémentation Media3, un ExoPlayer par son
│   ├── MixEngine                 état du mix, commandes, rampes de 20 ms, plan
│   ├── PreviewMixer              pré-écoute (sélecteur, éditeur de carte composée)
│   ├── AudioFocusController      focus audio, débranchement du casque
│   └── PlaybackService           MediaSessionService + lecteur de session
├── data/
│   ├── db/                       entités Room, DAO, base
│   ├── Models                    modèles de domaine immuables (Library, Card, Page…)
│   ├── LibraryRepository         source unique : flux `Library`, écritures transactionnelles
│   ├── SettingsStore             DataStore : fondus, thème, langue, dernier mix
│   ├── pack/                     manifeste, validation, import ZIP, pack de base
│   └── PageExchange              export et import de pages (JSON)
└── ui/
    ├── MainViewModel             état d'écran unique, événements, navigation
    ├── main/                     pages, grille, carte, barre basse, panneau « En cours »
    ├── editor/                   éditeur de page, sélecteur de cartes, carte composée, symboles
    ├── packs/                    import, liste, bilan
    ├── settings/                 réglages, licences des sons
    └── theme/                    thèmes clair et sombre
```

Flux unidirectionnel : l'interface envoie des commandes au `MainViewModel`, qui les relaie au `MixEngine` ou au `LibraryRepository` ; elle n'observe que des `StateFlow`. L'interface ne touche jamais un lecteur.

### Moteur de mixage

- `MixState` : cartes actives (ordonnées), `playing`, mode, plan (`cardId → PlanChange(toggle, volume)`), volumes connus, nombre de sons.
- Une *voix* par carte active ; chaque voix porte un `LoopPlayer` par son, une rampe de fondu et une rampe de volume.
- Gain d'un lecteur = courbe(volume carte) × courbe(volume relatif) × fondu² × fondu général².
- Un seul *ticker* de 20 ms fait avancer toutes les rampes et s'arrête quand plus rien ne bouge.
- Appliquer un plan : créer et préparer tous les nouveaux lecteurs à gain nul, attendre qu'ils soient prêts (1 s au plus), puis lancer toutes les rampes au même tick.
- Pause : rampe du fondu général vers 0 puis `pause()` des lecteurs ; les cartes restent actives. En pause, les voix ajoutées ne créent leurs lecteurs qu'au prochain *play*.
- Limite de 12 sons vérifiée à l'activation (mode direct) et à la mise au plan (mix projeté).
- Le moteur vit dans l'`Application` ; le service ne fait qu'exposer play/pause à la session média.

### Décisions prises (toutes réversibles)

| Sujet | Décision | Raison |
| --- | --- | --- |
| Identifiant | `io.github.mrlouix.sonorama` | Convention F-Droid pour un dépôt GitHub |
| Mix vide | La première carte activée lance la lecture ; un mix qui se vide repasse à l'arrêt | « Chaque action a un effet audible en moins d'une seconde » |
| Activation en pause | Silencieuse (spécification), avec un message « En pause » | Éviter de croire à une panne |
| Pack de base | 24 boucles **synthétisées** par `tools/generate_base_pack.py`, sous CC0 | Aucun enregistrement libre disponible à ce stade ; boucles sans raccord par construction ; remplaçables une à une |
| Format de pack | Un manifeste peut déclarer plusieurs pages ; le pack de base utilise le même format | Un seul chemin de code pour l'APK et les ZIP |
| Symboles | Jeu Material Icons (Apache 2.0) via `material-icons-extended`, identifiés par leur nom Material | Même licence et mêmes noms que Material Symbols, sans embarquer de police |
| Permission `WAKE_LOCK` | Ajoutée | Nécessaire à la lecture écran éteint pendant 2 h ; permission normale, sans invite |
| Réordonnancement | Glisser-déposer **et** boutons monter/descendre | Alternative accessible |
| Navigation | Pile d'écrans dans le ViewModel, sans bibliothèque de navigation | Six écrans, aucune URL profonde |

## 3. Modèle de données

| Table | Colonnes principales |
| --- | --- |
| `packs` | id, version, noms fr/en, intégré, installé |
| `sounds` | id (`pack/son`), pack, URI, noms fr/en, symbole, volume par défaut, auteur, licence, source, page, disponible |
| `cards` | id, composée, noms, symbole, volume d'ensemble |
| `card_sounds` | carte, son, volume relatif, position |
| `pages` | id, noms, symbole, intégrée, position |
| `page_cards` | page, carte, position |

Une carte simple a l'identifiant `s:<id du son>` : réimporter un pack retrouve donc ses cartes, leurs volumes et leur place sur les pages personnalisées. Désinstaller un pack marque ses sons indisponibles sans supprimer les cartes.

## 4. Ordre de construction

| Étape | Contenu | Vérification |
| --- | --- | --- |
| 0 | Projet Gradle, manifeste, thème, CI | `./gradlew assembleDebug` |
| 1 | `VolumeCurve`, `LoopPlayer`, `MixEngine` | Tests JVM : fondus, pause, plan, limite de 12 |
| 2 | `ExoLoopPlayer`, `PlaybackService`, focus audio | Compilation ; essai sur appareil |
| 3 | Room, `LibraryRepository`, `SettingsStore` | Compilation, schéma exporté |
| 4 | Manifeste, pack de base, générateur de sons | Tests JVM du manifeste ; pack généré |
| 5 | Écran principal : onglets, grille, carte, geste de volume, barre basse, « En cours » | Lint, essai sur appareil |
| 6 | Import ZIP, écran Packs, licences | Tests JVM des garde-fous d'archive |
| 7 | Pages personnalisées, sélecteur, cartes composées, scènes | Essai sur appareil |
| 8 | Réglages, langues, export/import de pages | Lint (chaînes manquantes) |
| 9 | Fichiers du dépôt : README, licence, contribution, modèles, métadonnées F-Droid | Relecture |

## 5. Stratégie de test

- **Tests JVM** (`./gradlew test`) : `MixEngine` avec de faux lecteurs et un temps virtuel, courbe de volume, validation des manifestes, contrôle des chemins d'archive, export de pages.
- **Lint Android** (`./gradlew lint`) : chaînes non traduites, permissions, API.
- **Essais sur appareil** (non automatisés) : raccord des boucles, charge de 12 lecteurs, lecture écran éteint, notification et casque Bluetooth, TalkBack. Ce sont les critères de sortie des lots 0 et 1 ; ils ne peuvent pas être validés sans téléphone.

## 6. Correspondance avec les lots

| Lot | Couvert par |
| --- | --- |
| 0 — Prototype audio | Étapes 1 et 2 |
| 1 — MVP | Étapes 3 à 5 |
| 2 — V1 | Étapes 6, 7 (pages), 8, 9 |
| 3 — V2 | Étapes 7 (cartes composées, scènes) et 8 (export/import de pages) |
