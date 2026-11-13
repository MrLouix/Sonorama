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
./gradlew assembleRelease               # APK optimisé, non signé
python3 tools/generate_base_pack.py     # régénère les 24 boucles (numpy, ffmpeg)
```

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
- L'appli n'a pas encore été essayée sur un appareil ; les sons du pack de base sont synthétisés.
