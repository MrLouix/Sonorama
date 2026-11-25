---
name: constituer-pack
description: Constituer un pack de sons Sonorama de bout en bout avec l'utilisateur — choix du thème et des sons, noms, symboles, mots-clés du mode vocal, licences, post-traitement des fichiers (boucle sans raccord, durée, niveau, compression Ogg), manifeste, vérification et ZIP. Utiliser dès qu'il est question de créer, compléter ou mettre à jour un pack, d'ajouter des sons à l'appli, ou de préparer un fichier audio pour Sonorama.
---

# Constituer un pack Sonorama

Un pack est un dossier `manifest.json` + `audio/*.ogg` + `LICENSES.md`, zippé puis importé dans l'appli.
Le format fait foi : `docs/format-pack.md`. Les règles de l'import sont dans
`app/src/main/kotlin/io/github/mrlouix/sonorama/data/pack/PackManifest.kt` ; `tools/check_pack.py` les reprend.

Travailler par étapes, en montrant chaque résultat à l'utilisateur avant de passer à la suivante.
Les décisions de contenu (thème, sons retenus, noms, licences) sont les siennes : proposer, puis attendre.

## 1. Cadrer

Demander, en une fois :
- le thème et l'usage (histoire pour enfants, jeu de rôle, quel univers) ;
- la page : une page intégrée à compléter (`nature`, `ville`, `technologique`, `meteo`, `transports`, `feerique`)
  ou une nouvelle page déclarée dans `pages` ;
- le nombre de sons (4 à 8 par page se lisent bien ; 24 cartes au plus par page) ;
- l'origine des sons : générés par IA (skill `generer-sons-ia`), enregistrements libres existants, ou
  enregistrements de l'utilisateur ;
- un identifiant de pack stable (`auteur.theme`, minuscules) : le garder d'une version à l'autre.

Hors périmètre, à signaler si demandé : les sons ponctuels joués une fois (porte, cloche, coup de tonnerre
isolé). Sonorama ne joue que des **boucles d'ambiance**. Un évènement se transforme en ambiance qui le
contient de temps en temps (« orage » plutôt que « coup de tonnerre »).

## 2. Proposer le catalogue

Présenter un tableau, un son par ligne :

| Champ | Règle |
| --- | --- |
| `id` | `[a-z0-9][a-z0-9._-]*`, unique, stable entre versions (il porte le volume et la place des cartes) |
| `name.fr` / `name.en` | 60 caractères au plus ; viser **20** : la carte n'a que deux lignes |
| `symbol` | Un nom présent dans `app/src/main/kotlin/io/github/mrlouix/sonorama/ui/Symbols.kt` (sinon note de musique) |
| `defaultVolume` | 0–100 ; 50–70 pour un fond, plus bas pour un son envahissant (bips, bourdonnement) |
| `keywords.fr` / `keywords.en` | Voir ci-dessous |

**Mots-clés du mode vocal** :
- 3 à 8 par langue ; noms et verbes qu'un conteur prononce vraiment (« il pleut », « l'orage gronde »).
- Chaque forme utile séparément : singulier et pluriel (`loup`, `loups`), verbes conjugués courants.
- Éviter les mots trop fréquents qui déclencheraient sans cesse (« eau », « nuit », « bruit », « temps »).
- Un même mot sur deux cartes d'une page les lance ensemble : le faire exprès (« orage » → pluie + tonnerre),
  jamais par accident.
- Pas de noms propres ni de mots inventés : le modèle Vosk ne les connaît pas. Les vérifier avec
  `check_pack.py --vocab` (étape 5).

## 3. Obtenir les sons

- **Générés par IA** : suivre le skill `generer-sons-ia` ; il produit des WAV et un `generation.json`.
- **Enregistrements existants** : seulement CC0, domaine public, CC BY ou CC BY-SA. Jamais NC ni ND, jamais
  « usage personnel », jamais un son sans licence écrite. Noter auteur, licence exacte et adresse de la page.
  Sur Freesound, filtrer par licence et vérifier la licence affichée sur la page du son.
- **Enregistrements de l'utilisateur** : il en est l'auteur ; lui proposer CC0 ou CC BY 4.0.

Prévoir pour chaque son une source **plus longue que la boucle visée** : durée + 3 s de fondu au moins.

## 4. Post-traiter chaque fichier

Exigences du format (spécification, section 8) et réglages du pack de base :

| Critère | Valeur |
| --- | --- |
| Format | Ogg Vorbis, seul format lu par l'appli ; extension `.ogg` |
| Échantillonnage | 44,1 kHz, stéréo (un mono est dupliqué) |
| Durée de la boucle | 30 à 120 s ; **60 s conseillées**. Trop court : la répétition s'entend. Trop long : le pack grossit |
| Raccord | Inaudible : fondu enchaîné de bouclage de 2 à 4 s, à puissance constante |
| Niveau | −23 LUFS, crête réelle ≤ −1,5 dBTP : tous les sons au même niveau perçu |
| Compression | Vorbis qualité 4 (≈ 128 kbit/s), sans métadonnées : ≈ 1 Mo par minute |
| Contenu | Pas de silence en tête ou en queue, pas de fondu d'entrée ou de sortie, pas d'évènement marquant près du raccord |

Une commande fait tout et contrôle le résultat :

```bash
python3 tools/prepare_loop.py source.wav mon-pack/audio/pluie.ogg                    # 60 s, fondu 3 s
python3 tools/prepare_loop.py source.wav mon-pack/audio/pluie.ogg --duration 40      # source courte
python3 tools/prepare_loop.py a.wav b.wav --concat --out mon-pack/audio/foret.ogg    # extraits bout à bout
python3 tools/prepare_loop.py --check mon-pack/audio/*.ogg                           # contrôle seul
```

- Un `⚠` est bloquant tant qu'il n'est pas compris. « Saut au raccord » : augmenter `--crossfade`.
  « Niveau change au raccord » : l'outil a déjà cherché le meilleur passage ; en imposer un autre avec `--skip`, ou
  laisser l'écoute trancher pour un son irrégulier (gouttes, vagues). « Niveau perçu » : des pics isolés bloquent le
  limiteur ; augmenter `--limiting` (8 dB par défaut) si l'écoute le permet.
- Une source de moins de 33 s demande `--repeat`.
- `--concat` assemble des extraits différents : le passage de l'un à l'autre peut s'entendre. Préférer
  une seule source assez longue, sinon le signaler à l'utilisateur.
- Les mesures ne remplacent pas l'écoute : demander à l'utilisateur d'écouter chaque boucle **en
  boucle**, au moins deux tours, en prêtant attention au raccord et à un évènement qui se répéterait.

## 5. Écrire le manifeste et les licences

- `manifest.json` en UTF-8, `"format": 1` (ou 2 si le pack sera signé), `version` à incrémenter à chaque
  publication. Exemple complet dans `docs/format-pack.md`.
- `source` : adresse de la page d'origine ; pour un son généré, le modèle et l'invite
  (`Stable Audio Open 1.0 — "steady rain on a tin roof" — graine 3`).
- `author` obligatoire pour CC BY ; pour un son généré par IA, l'utilisateur (qui l'a choisi et traité).
- `LICENSES.md` : un tableau fichier / nom / auteur / licence / source, comme
  `app/src/main/assets/basepack/LICENSES.md`.

Puis vérifier, en corrigeant jusqu'à zéro erreur :

```bash
python3 tools/check_pack.py mon-pack/
python3 tools/check_pack.py mon-pack/ --vocab fr=chemin/vosk-model-small-fr-0.22 --vocab en=chemin/vosk-model-small-en-us-0.15
```

`--vocab` demande `pip install vosk` et les modèles décompressés (https://alphacephei.com/vosk/models) ;
un mot-clé signalé inconnu ne sera jamais reconnu : le remplacer.

## 6. Fabriquer le ZIP

- **Non signé** : `python3 tools/check_pack.py mon-pack/ --zip` → `mon-pack.zip`.
- **Signé** (recommandé pour un pack publié) : `python3 tools/sign_pack.py sign mon-pack/ ma-cle.pem`.
  La clé privée appartient à l'utilisateur : **ne jamais en créer une, ni lire, copier ou commiter un `.pem`
  dans une session d'assistant**. L'utilisateur lance lui-même `keygen` puis `sign` sur sa machine.
- Publier le ZIP avec les deux empreintes qu'affiche `sign_pack.py` (ou `sign_pack.py fingerprint`).

## 7. Faire essayer

Importer le ZIP sur le téléphone (Packs → Importer), puis vérifier avec l'utilisateur : noms lisibles sur
les cartes, niveaux homogènes avec le pack de base, raccords inaudibles, mots-clés reconnus en lisant une
phrase à voix haute. Pour une mise à jour : même `id`, `version` + 1, mêmes `id` de sons.

## Ne jamais

- Mettre dans un pack un son dont la licence n'est pas écrite et compatible (CC0, CC BY, CC BY-SA).
- Déclarer CC0 un son tiers qui ne l'est pas, ou effacer l'auteur d'un son CC BY.
- Commiter un pack dans ce dépôt sans que l'utilisateur le demande (seul le pack de base y vit, régénéré
  par `tools/generate_base_pack.py` et `tools/prepare_recordings.py`).
