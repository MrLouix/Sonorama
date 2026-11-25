# Consignes pour les sessions Claude

## GitHub Actions : stockage des artefacts

Le compte ne dispose que de 0,5 Go de stockage Actions par mois, partagé entre tous les dépôts privés. En novembre 2026, ce quota a été dépassé par des artefacts lourds conservés 90 jours.

Pour tout workflow créé ou modifié :

- Chaque étape `actions/upload-artifact` fixe `retention-days` : 3 jours au plus pour un binaire (installateur, APK, archive), 7 jours au plus pour des rapports ou des journaux.
- Ne pas envoyer d'artefact si le fichier est déjà publié ailleurs (release, registre de paquets).
- Ne pas envoyer de binaire lourd à chaque push : le limiter à `main`, aux tags ou à `workflow_dispatch` quand c'est possible.
- Éviter de déclencher deux fois le même travail (`push` et `pull_request` sur la même branche).

Le dépôt est public : ses artefacts ne comptent pas dans ce quota aujourd'hui. Les règles s'appliquent quand même, pour qu'un passage en privé ou une copie du workflow ailleurs ne coûte rien.

## Commandes

Prérequis : JDK 21 et SDK Android (plateforme 37).

```bash
./gradlew lintDebug testDebugUnitTest   # ce que lance la CI, avec assembleDebug
./gradlew assembleDebug
./gradlew assembleRelease               # APK optimisés, un par processeur, non signés
python3 tools/generate_base_pack.py     # boucles synthétisées, manifeste et licences (numpy, ffmpeg avec libvorbis)
python3 tools/prepare_recordings.py     # boucles enregistrées acceptées, depuis pack-sources/ (non versionné)
python3 tools/prepare_loop.py src out.ogg   # boucle prête pour un pack ; --check pour contrôler
python3 tools/check_pack.py dossier/ --zip  # vérifie un pack comme l'appli, puis le zippe
```

Pour un pack de sons, suivre les skills `.claude/skills/constituer-pack` et `.claude/skills/generer-sons-ia`.

Les tests d'écran (`ScreensTest`, Robolectric) écrivent des captures dans `app/build/screens/` : les regarder après tout changement d'interface.

## Conventions

- Langue : documentation, commentaires et chaînes par défaut en français ; identifiants du code en anglais.
- La spécification fait foi : `docs/specification.md`. Architecture et décisions : `docs/plan-de-codage.md`.
- L'interface ne touche jamais un lecteur audio : elle passe par `MixEngine` et observe son état.
- Tout comportement du moteur a un test dans `MixEngineTest`.
- Toute chaîne visible existe dans `values/` (anglais) et `values-fr/`.
- Jamais de permission `INTERNET`, ni de dépendance propriétaire (compatibilité F-Droid).

## Pièges connus

- Les cartes de la grille portent leur nom dans leur description d'accessibilité : dans les tests, les chercher avec `hasContentDescription`, pas `onNodeWithText`.
- `InputStream.readNBytes` n'existe qu'à partir d'Android 13 : utiliser `readAtMost` (`PackArchive.kt`).
- L'appli n'a pas encore été essayée sur un appareil ; 14 des 33 sons du pack de base sont encore synthétisés.
- Un enregistrement n'entre dans le pack de base qu'une fois écouté par l'utilisateur : `accepted` dans `tools/recordings.json`. Augmenter alors `--version` dans `generate_base_pack.py`, sinon les appareils déjà installés gardent l'ancien manifeste.
- Toute modification du schéma Room demande une migration et un cas dans `MigrationTest` ; le schéma exporté est dans `app/schemas/`.
- Une clé privée de signature de pack (`*.pem`) ne se commite jamais et ne se crée pas dans une session d'assistant : `OFFICIAL_KEYS` ne reçoit que des empreintes fournies par le propriétaire.
- La première compilation télécharge le modèle Vosk français depuis alphacephei.com (empreinte vérifiée, cache `~/.gradle/caches/sonorama-vosk`) : l'hôte doit être joignable.
- La transcription du mode vocal vit dans `src/debug/` et ne doit jamais passer dans `src/main/` : l'APK de publication ne la contient pas. `src/release/` en garde une version vide de même signature.
- Une grammaire Vosk s'envoie en UTF-8 brut : un mot échappé (`\u00ea`) est ignoré.
- `tools/check_pack.py` recopie les règles de `PackManifest.kt` : toute règle d'import modifiée se reporte dans les deux.
- Aucun son ElevenLabs dans un pack ni dans l'APK : ses conditions interdisent la distribution des effets seuls ou en collection.
- Les clés des listes Compose (`LazyColumn`, `LazyVerticalGrid`) viennent d'identifiants, jamais de noms affichés : un pack peut répéter un nom.
