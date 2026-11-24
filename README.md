# Sonorama

[![Licence : GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-blue.svg)](LICENSE)

Sonorama est une appli Android qui joue des sons d'ambiance en boucle, mixés en direct depuis des pages de
cartes tactiles. Elle sert à lire des histoires aux enfants et à mener des parties de jeu de rôle : une grille
de cartes, un bouton play/pause, aucun compte, aucune publicité, aucun accès réseau.

<p>
  <img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/2.png" width="24%" alt="Deux cartes actives sur la page Nature">
  <img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/3.png" width="24%" alt="Mode plan : deux cartes en attente d'activation">
  <img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/4.png" width="24%" alt="Panneau En cours avec un curseur de volume par carte">
  <img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/6.png" width="24%" alt="Thème sombre">
</p>

## Ce que fait l'appli

- **Cartes** : un appui lance ou arrête un son, toujours en fondu. Jusqu'à 12 sons en même temps.
- **Mode plan** : les appuis préparent des changements sur plusieurs pages ; « Appliquer » les déclenche
  ensemble, en fondu croisé.
- **Volume par carte** : appui long, puis glissement vertical ; le niveau est mémorisé.
- **Play/pause général** : le mix s'éteint et reprend en fondu ; il est restauré en pause à la réouverture.
- **Arrière-plan** : lecture écran éteint, commandes dans la notification, sur l'écran verrouillé et au casque.
- **Pages personnalisées** : vos cartes favorites sur une page à vous, exportable en fichier.
- **Cartes composées et scènes** : plusieurs sons sous une seule carte, ou le mix en cours enregistré tel quel.
- **Mode vocal** : l'appli écoute la personne qui lit et lance pendant quelques secondes les cartes dont elle
  entend un mot-clé, en plus du mix en cours. Reconnaissance hors ligne (Vosk), micro coupé en pause.
- **Packs de sons** : 24 boucles fournies, d'autres par fichiers ZIP importés depuis le téléphone ; les
  packs peuvent être signés, et toute mise à jour demande confirmation.
- **Accessibilité** : TalkBack, grandes cibles tactiles, thèmes clair et sombre, français et anglais.

## État du projet

Le code couvre les lots 0 à 3 de la [spécification](docs/specification.md) et le mode vocal (section 14). Il
compile, passe le lint et 69 tests automatisés, dont des tests d'écran qui pilotent l'appli réelle sur JVM.

**Il n'a pas encore tourné sur un téléphone.** Restent donc à valider sur appareil, avant toute publication :

- le raccord des boucles et la charge de 12 lecteurs sur un téléphone d'entrée de gamme (risque du lot 0) ;
- la lecture écran éteint pendant 2 h, la notification média et les commandes Bluetooth ;
- le geste de volume et le parcours TalkBack ;
- la reconnaissance vocale réelle : précision sur une vraie voix, délai, écoute écran éteint, sons de l'appli
  captés par le micro ;
- la qualité des 24 sons du pack de base, qui sont **synthétisés** et n'ont pas été écoutés ni comparés à
  des enregistrements.

Les points ouverts de la spécification (marque « Sonorama », pack de base enregistré) restent à traiter.

## Compiler

Prérequis : JDK 21 et SDK Android (plateforme 37). Avec Android Studio, ouvrez simplement le dossier.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-<processeur>-debug.apk
./gradlew testDebugUnitTest    # tests ; captures d'écran dans app/build/screens/
./gradlew lintDebug
./gradlew assembleRelease      # APK optimisés, un par processeur, non signés
```

Un APK est produit par type de processeur : `arm64-v8a` convient à presque tous les téléphones récents.
La première compilation télécharge le modèle vocal français officiel de Vosk (42 Mo, depuis alphacephei.com),
vérifie son empreinte et le garde en cache dans le dossier Gradle de l'utilisateur.

Le pack de base se régénère avec `python3 tools/generate_base_pack.py` (Python 3.10+, numpy, ffmpeg).

## Organisation du dépôt

| Chemin | Contenu |
| --- | --- |
| `app/src/main/kotlin/…/audio` | Moteur de mixage (`MixEngine`), lecteurs Media3, service de lecture |
| `app/src/main/kotlin/…/data` | Base Room, dépôt, réglages, import de packs, export de pages |
| `app/src/main/kotlin/…/ui` | Écrans Jetpack Compose et `MainViewModel` |
| `app/src/main/kotlin/…/voice` | Mode vocal : reconnaissance Vosk, mots-clés, service micro |
| `app/src/debug` | Transcription du texte entendu, présente seulement dans l'APK de débogage |
| `app/src/main/assets/basepack` | Pack de base : manifeste, 24 boucles Ogg, licences |
| `app/src/test` | Tests du moteur, des packs et des écrans |
| `tools/generate_base_pack.py` | Synthèse des boucles du pack de base |
| `tools/sign_pack.py` | Signature d'un pack de sons |
| `docs/` | [Spécification](docs/specification.md), [plan de codage](docs/plan-de-codage.md), [format des packs](docs/format-pack.md) |
| `fastlane/metadata` | Fiche de l'appli pour F-Droid |

## Fabriquer un pack de sons

Un pack est une archive ZIP contenant un `manifest.json`, des boucles Ogg Vorbis et un `LICENSES.md`.
Le format est décrit dans [docs/format-pack.md](docs/format-pack.md), avec la signature des packs ; c'est
aussi la voie pour ajouter ses propres sons.

## Vie privée

Sonorama ne demande ni accès réseau ni accès au stockage. Ses permissions sont le service de premier plan
(lecture média et micro), les notifications, le maintien du processeur éveillé pendant la lecture et le micro.
Le micro n'est demandé qu'au premier usage du mode vocal et n'est ouvert que pendant l'écoute ; la
reconnaissance se fait sur le téléphone et aucun son n'est enregistré. Rien n'est collecté, rien n'est envoyé.

## Contribuer

Voir [CONTRIBUTING.md](CONTRIBUTING.md) et le [code de conduite](CODE_OF_CONDUCT.md).
Les failles de sécurité se signalent en privé : [SECURITY.md](SECURITY.md).

## Licences

- Code : [GNU GPL 3.0](LICENSE).
- Sons du pack de base : [CC0 1.0](app/src/main/assets/basepack/LICENSES.md), créations originales synthétisées.
- Pictogrammes : Material Icons de Google, licence Apache 2.0.
- Reconnaissance vocale : [Vosk](https://alphacephei.com/vosk/) et son modèle français
  `vosk-model-small-fr-0.22`, licence Apache 2.0 ; JNA, licence Apache 2.0.

---

**English summary.** Sonorama is an Android app that plays looping ambient sounds, mixed live from pages of
touch cards, for story time and tabletop role-playing games. An offline voice mode starts cards when it hears
their keywords while the story is read. No account, no ads, no network access. It is
written in Kotlin with Jetpack Compose and Media3, licensed under GPL-3.0, and available in French and English.
The code builds and its automated tests pass, but it has not yet been run on a physical device.
