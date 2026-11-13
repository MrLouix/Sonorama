# Contribuer à Sonorama

Merci de votre intérêt ! Sonorama est un logiciel libre sous [GPL-3.0](LICENSE) ; les contributions de code,
de sons, de traductions et de retours d'usage sont bienvenues. En participant, vous acceptez le
[code de conduite](CODE_OF_CONDUCT.md).

## Avant de commencer

- Lisez la [spécification](docs/specification.md) : elle fixe le périmètre et ce qui en est exclu
  (pas de musique, pas de compte, pas d'accès réseau, pas de publicité).
- Pour un changement important, ouvrez d'abord un ticket afin d'en discuter.
- Le [plan de codage](docs/plan-de-codage.md) décrit l'architecture et les choix déjà faits.

## Mettre en place l'environnement

Il faut un JDK 21 et le SDK Android (plateforme 37). Android Studio installe les deux.

```bash
./gradlew assembleDebug        # compile l'APK de débogage
./gradlew testDebugUnitTest    # tests JVM, y compris les tests d'écran
./gradlew lintDebug            # analyse statique Android
```

Les tests d'écran écrivent des captures dans `app/build/screens/` : regardez-les après un changement d'interface.

## Règles pour le code

- Kotlin, style officiel ; indentation et longueur de ligne dans [`.editorconfig`](.editorconfig).
- L'interface ne parle jamais aux lecteurs audio : elle envoie des commandes au `MixEngine` et observe son état.
- Toute transition sonore passe par un fondu ; aucune coupure sèche.
- Tout comportement du moteur de mixage est couvert par un test dans `MixEngineTest`.
- Toute chaîne visible existe en français (`values-fr`) et en anglais (`values`).
- Chaque élément tactile a une description pour TalkBack ; un état ne repose jamais sur la couleur seule.
- Aucune dépendance propriétaire (Google Play Services, Firebase, suivi, publicité) : l'appli doit rester
  compatible F-Droid.
- La permission `INTERNET` ne doit jamais être ajoutée.

## Proposer un changement

1. Créez une branche depuis `main`.
2. Faites des commits courts, au message explicite à l'impératif (« Ajoute… », « Corrige… »).
3. Vérifiez que `./gradlew lintDebug testDebugUnitTest` passe.
4. Ajoutez une ligne au [journal des modifications](CHANGELOG.md), section « Non publié ».
5. Ouvrez une demande de fusion en remplissant le modèle.

## Contribuer des sons

Les sons se partagent sous forme de packs : voir le [format des packs](docs/format-pack.md).

- Licences acceptées : **CC0** de préférence, **CC BY** avec attribution. Jamais « NC » ni « ND ».
- Vous devez être l'auteur du son ou pouvoir citer sa source et sa licence.
- Boucles Ogg Vorbis, 44,1 kHz, stéréo, de 30 à 120 s, sans raccord audible, niveau normalisé.
- Les sons du pack de base sont produits par [`tools/generate_base_pack.py`](tools/generate_base_pack.py) ;
  pour en remplacer un par un enregistrement, proposez le fichier, sa source et sa licence dans un ticket.

## Traduire

Copiez `app/src/main/res/values/strings.xml` dans `values-<code de langue>/` et traduisez les valeurs.
Les noms des sons se traduisent dans le manifeste de chaque pack, qui n'accepte aujourd'hui que `fr` et `en`.
