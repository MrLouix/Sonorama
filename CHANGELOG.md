# Journal des modifications

Ce fichier suit le format [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
le projet applique le [versionnage sémantique](https://semver.org/lang/fr/).

## [Non publié]

### Ajouté

- Mode vocal : bouton micro dans la barre du bas ; les cartes de la page affichée dont un mot-clé est
  entendu jouent pendant une durée réglable (3 à 30 s), en plus du mix. Reconnaissance hors ligne par Vosk,
  écoute écran éteint, micro coupé en pause, arrêt automatique après 5 minutes sans mot-clé.
- Mots-clés par carte et par langue, déclarés par les packs (champ `keywords`) et modifiables depuis
  l'onglet d'une page ; mots inconnus du modèle signalés. Mots-clés fournis pour les 24 sons du pack de base.
- Modèle vocal français intégré ; modèle anglais officiel importable. Langue de l'histoire réglable.
- Version de débogage seulement : transcription du texte entendu, exportable en CSV.
- Un APK par type de processeur.
- Une carte lancée à la voix peut être gardée en lecture d'un appui sur son anneau (épingle) : elle
  rejoint alors le mix comme une carte lancée à la main.
- Outils de pack : `tools/prepare_loop.py` (boucle sans raccord, niveau et Ogg du pack de base, contrôle),
  `tools/check_pack.py` (règles de l'appli, durée et format audio, symboles, vocabulaire Vosk, ZIP),
  `tools/generate_ambience.py` (Stable Audio Open en local).
- Skills Claude Code `constituer-pack` et `generer-sons-ia`.

- Signature des packs (ECDSA P-256) : mention « officiel », « signé » ou « non signé », clé mémorisée au
  premier import, outil `tools/sign_pack.py`. Format de pack 2, le format 1 reste lisible.
- Confirmation avant toute mise à jour d'un pack, avec l'empreinte du fichier affichée à la demande.

- Écran principal : pages à onglets, grille de cartes, barre de commande, panneau « En cours ».
- Moteur de mixage : jusqu'à 12 sons en boucle, fondus d'entrée et de sortie, pause et reprise du mix.
- Mode plan : changements préparés sur plusieurs pages, appliqués ensemble en fondu croisé.
- Volume par carte, par appui long puis glissement vertical, mémorisé entre les sessions.
- Lecture en arrière-plan avec notification média, écran verrouillé et casque Bluetooth.
- Pack de base de 24 boucles synthétisées sous CC0 : Nature, Ville, Technologique, Météo, Transports.
- Pages personnalisées : création, édition, réordonnancement, duplication, export et import.
- Cartes composées et scènes (enregistrement du mix en cours).
- Import de packs ZIP depuis le téléphone, avec vérifications et bilan ; écran des licences des sons.
- Réglages : durées de fondu, thème clair ou sombre, langue (français, anglais).

### Corrigé

- Barre de commande et noms de cartes lisibles sur écran étroit et jusqu'à 200 % de taille de police.
- Les messages (limites atteintes, refus) s'affichent sur l'écran où ils se produisent.
- La pré-écoute s'arrête en quittant l'appli, sur un appel et au débranchement du casque.
- Relancer la lecture pendant un appel ne joue plus par-dessus l'appel.
- Un son illisible arrête sa carte au lieu de la laisser affichée active en silence.
- La limite de 12 sons est revérifiée à l'application d'un plan.
- En pause, les lecteurs sont rendus au système.
- L'export d'une page survit à une rotation de l'écran.
- La confirmation et le bilan d'import s'affichent quel que soit l'écran.
- Un son qui change de page dans une mise à jour de pack quitte l'ancienne page.
- Les icônes de la barre d'état suivent le thème choisi dans l'appli.

### Sécurité

- Un pack dont deux pages portent le même nom ne fait plus planter le sélecteur de cartes.
- Liste fermée de licences : les variantes « NonCommercial » et « NoDerivs » ne passent plus.
- Remplacement d'un pack : l'ancien contenu n'est supprimé qu'une fois le nouveau en place.
- Longueur maximale pour les champs libres du manifeste.
- Somme de contrôle de la distribution Gradle dans le wrapper.
