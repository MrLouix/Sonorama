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
| Reconnaissance vocale | Vosk (Android) et JNA, modèle `vosk-model-small-fr-0.22` intégré | 0.3.75 / 5.18.1 |
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
│   ├── Keywords                  mots-clés : stockage, nettoyage, forme normalisée
│   └── PageExchange              export et import de pages (JSON)
├── voice/
│   ├── KeywordMatcher            repère les mots-clés dans le texte reconnu
│   ├── SpeechEngine(+Vosk…)      reconnaissance abstraite (testable) et implémentation Vosk
│   ├── SpeechModels              modèles acceptés (empreintes), extraction, import
│   ├── VoiceController           état du mode vocal, page affichée, pause, arrêt automatique
│   └── VoiceService              service au premier plan de type « micro »
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
- Visite guidée : `setAside()` met de côté les cartes durables, la lecture, le mode et le plan, puis vide le mix ; `bringBack()` arrête ce que la visite a lancé, rend aux cartes réglées pendant la visite leur volume d'avant (mémorisé au premier réglage, réenregistré) et rétablit le mix comme `restore`, repris s'il jouait. Pendant ce temps, le dernier mix n'est pas réenregistré, et un `restore` du lancement attend la fin de la visite.
- Mode vocal : `trigger(cartes, durée)` lance des voix minutées (fondus fixes de 0,5 s et 1 s), prolongées si le mot revient. Elles comptent dans la limite ; à la limite, la plus ancienne voix minutée cède sa place. Elles ne sont jamais enregistrées comme dernier mix. Une pause les arrête ; `paused` reste vrai même si le mix s'est vidé, pour que le micro reste coupé. `keep(carte)` retire le minutage : la voix devient une voix ordinaire.

### Mode vocal

- `VoiceController` observe le mix, la bibliothèque et les réglages ; il ouvre le micro quand le mode est allumé et hors pause, le ferme sinon. Le micro rouvert après une pause relance le délai d'arrêt automatique (5 min sans mot-clé).
- Le texte partiel est comparé aux mots-clés de la page affichée (`KeywordMatcher`) : un mot-clé déclenche quand son nombre d'occurrences dans la phrase augmente, ce qui absorbe les répétitions et corrections des résultats partiels. Une occurrence compte si sa confiance atteint 0,6 ou si elle figure dans 4 résultats partiels de suite (un toutes les 0,1 s).
- Vosk : `AudioRecord` 16 kHz mono, source `VOICE_RECOGNITION`, annulation d'écho si disponible. La grammaire est envoyée en UTF-8 brut (les échappements `\u00ea` font ignorer le mot). `vosk_model_find_word`, absente de l'API Java, est appelée par JNA pour repérer les mots inconnus. Fin de phrase après 1 s de silence (0,5 s par défaut dans Vosk), pour ne pas découper la lecture. Avec `setMaxAlternatives`, Vosk ne donne plus la confiance par mot : les deux ne se cumulent pas.
- Voix d'enfant (réglage de débogage) : `LoweredRecognizer` étire le signal de 16 à 19 kHz (`Stretcher`) sur son propre fil, avec un détecteur de mots-clés à part, et repart d'un reconnaisseur neuf toutes les 30 s environ, en fin de phrase.
- Le modèle français est téléchargé par Gradle (`downloadVoskModel`, empreinte vérifiée, cache dans le dossier Gradle de l'utilisateur) puis placé dans les assets ; il s'extrait dans le dossier privé au premier usage. Le modèle anglais s'importe : seule l'empreinte d'un modèle officiel connu est acceptée.
- La transcription n'existe que dans `src/debug/` (`VariantFeatures`) ; `src/release/` en fournit une version vide.

### Décisions prises (toutes réversibles)

| Sujet | Décision | Raison |
| --- | --- | --- |
| Identifiant | `io.github.mrlouix.sonorama` | Convention F-Droid pour un dépôt GitHub |
| Mix vide | La première carte activée lance la lecture ; un mix qui se vide repasse à l'arrêt | « Chaque action a un effet audible en moins d'une seconde » |
| Activation en pause | Silencieuse (spécification), avec un message « En pause » | Éviter de croire à une panne |
| Pack de base | 36 boucles sous CC0 : 22 enregistrements de Freesound et 3 sons générés par Stable Audio Open (`tools/prepare_recordings.py`), et 11 sons **synthétisés** (`tools/generate_base_pack.py`) | Un son synthétisé est remplacé quand un enregistrement libre ou un son généré a été écouté et accepté ; sources hors de Git, recette versionnée dans `tools/recordings.json` |
| Niveau des sons | −17 LUFS intégrés, crête réelle ≤ −1,5 dBTP | À RMS égal, l'écart perçu dépassait 17 LU entre un son grave et un son aigu |
| Format de pack | Un manifeste peut déclarer plusieurs pages ; le pack de base utilise le même format | Un seul chemin de code pour l'APK et les ZIP |
| Symboles | Jeu Material Icons (Apache 2.0) via `material-icons-extended`, identifiés par leur nom Material | Même licence et mêmes noms que Material Symbols, sans embarquer de police |
| Permission `WAKE_LOCK` | Ajoutée | Nécessaire à la lecture écran éteint pendant 2 h ; permission normale, sans invite |
| Réordonnancement | Glisser-déposer **et** boutons monter/descendre | Alternative accessible |
| Navigation | Pile d'écrans dans le ViewModel, sans bibliothèque de navigation | Six écrans, aucune URL profonde |
| Garder un son vocal | Un appui sur la carte la garde, un second l'arrête ; l'anneau porte une épingle | Aucun bouton de plus ; une cible minuscule ferait arrêter le son par erreur pendant la lecture |
| Visite guidée | Voile Compose sur l'écran réel (`ui/tour/GuidedTour.kt`) : les éléments se déclarent par `Modifier.tourTarget`, quatre bandes bloquent les appuis autour de l'élément montré ; l'état vit dans `MainViewModel`, le mix mis de côté dans le moteur | Apprendre les gestes sur l'interface qu'on utilisera ; aucune bibliothèque tierce |
| Mots-clés | Mode paramétrage : un appui sur une carte ouvre ses mots-clés ; toute la page depuis son onglet (appui long, « Mots-clés ») | L'appui long sur une carte règle déjà le volume |
| Reconnaissance | Phrase entière par défaut ; mode limité aux mots-clés comparable dans l'APK de débogage | Essai sur voix de synthèse : le mode limité invente des mots-clés |
| APK | Un APK par processeur, codes de version `version × 10 + processeur` ; l'AAB du Play Store n'est pas découpé (`bundleRelease`, code `version × 10`) et se signe par une clé d'envoi lue dans `keystore.properties` | Vosk ajoute environ 10 Mo de code natif par processeur ; le Play Store découpe l'AAB lui-même, et AGP refuse les APK multiples en le fabriquant |

## 3. Modèle de données

| Table | Colonnes principales |
| --- | --- |
| `packs` | id, version, noms fr/en, intégré, installé, clé du signataire, empreinte de l'archive |
| `sounds` | id (`pack/son`), pack, URI, noms fr/en, symbole, volume par défaut, auteur, licence, source, page, disponible, mots-clés du pack |
| `cards` | id, composée, noms, symbole, volume d'ensemble, mots-clés de l'utilisateur (null : ceux du pack) |
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
| 10 | Mode vocal : voix minutées, mots-clés, Vosk, service micro, transcription de débogage | Tests JVM (`VoiceTest`, `MixEngineTest`) ; essai sur appareil |

## 5. Stratégie de test

- **Tests JVM** (`./gradlew testDebugUnitTest`) : `MixEngine` avec de faux lecteurs et un temps virtuel, courbe de volume, validation des manifestes, contrôle des chemins d'archive, export de pages.
- **Tests d'écran sur JVM** (Robolectric) : l'appli réelle — base, pack de base, moteur — pilotée à travers l'interface Compose : pages intégrées, mode direct, mode plan, création d'une page, écrans secondaires. Chaque test écrit une capture dans `app/build/screens/`.
- **Lint Android** (`./gradlew lint`) : chaînes non traduites, permissions, API.
- **Mode vocal sur JVM** : `VoiceTest` pilote `VoiceController` avec un faux reconnaisseur (page, pause, arrêt automatique, transcription) ; Vosk lui-même n'est pas chargé.
- **Essais sur appareil** (non automatisés) : raccord des boucles, charge de 12 lecteurs, lecture écran éteint, notification et casque Bluetooth, TalkBack, reconnaissance vocale réelle (précision, délai, écran éteint). Ce sont les critères de sortie des lots 0 et 1 ; ils ne peuvent pas être validés sans téléphone.

## 6. Correspondance avec les lots

| Lot | Couvert par |
| --- | --- |
| 0 — Prototype audio | Étapes 1 et 2 |
| 1 — MVP | Étapes 3 à 5 |
| 2 — V1 | Étapes 6, 7 (pages), 8, 9 |
| 3 — V2 | Étapes 7 (cartes composées, scènes) et 8 (export/import de pages) |
