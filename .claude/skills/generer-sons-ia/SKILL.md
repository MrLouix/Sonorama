---
name: generer-sons-ia
description: Générer des sons d'ambiance par IA pour un pack Sonorama (Stable Audio Open en local, ou autre outil) en respectant les licences exigées par les packs — choix de l'outil selon ses conditions d'utilisation, rédaction des invites, génération de variantes, traçabilité, puis passage au post-traitement. Utiliser dès que l'utilisateur veut créer des sons avec une IA (ElevenLabs, Stable Audio, AudioGen, Firefly…) ou demande d'où tirer de nouveaux sons sans enregistrement.
---

# Générer des sons d'ambiance par IA

Les sons d'un pack doivent pouvoir être **redistribués seuls, sous CC0 ou CC BY** (`docs/format-pack.md`,
« Licences acceptées »). C'est le premier critère, avant la qualité : un outil dont les conditions interdisent
cette redistribution est exclu, quel que soit son rendu. La suite du travail (boucle, niveau, Ogg,
manifeste) relève du skill `constituer-pack`.

## 1. Choisir l'outil

État vérifié le 24 nov. 2026, d'après des extraits des pages citées ; **relire la page à jour avant
chaque nouveau pack** et prévenir l'utilisateur si une condition a changé.

| Outil | Pour un pack | Raison |
| --- | --- | --- |
| **Stable Audio Open 1.0**, en local (`tools/generate_ambience.py`) | **Recommandé** | Stability AI Community License : les sons produits appartiennent à l'utilisateur, redistribuables. Gratuit sous un plafond de chiffre d'affaires (1 M$ par an) ; https://stability.ai/license |
| Stable Audio 3.0 (Small, Medium, Small SFX), poids ouverts | Possible | Même licence ; le script vise la 1.0, l'adapter si `diffusers` prend en charge la 3.0 |
| Synthèse procédurale (`tools/generate_base_pack.py`) | Possible | Sons originaux, CC0 sans réserve ; rendu plus artificiel |
| **ElevenLabs** (effets sonores) | **Exclu** | Les conditions ElevenCreative interdisent de distribuer les effets seuls, en fichiers isolés ou dans une collection ou banque de sons : c'est ce qu'est un pack. Offre gratuite : ni usage commercial ni absence d'attribution. ElevenLabs peut aussi partager les sons générés avec d'autres utilisateurs. https://elevenlabs.io/creative-terms, https://elevenlabs.io/sound-effects-terms |
| Meta AudioGen (AudioCraft) | À éviter | Poids sous CC BY-NC 4.0 ; rien ne garantit que les sons produits puissent passer sous CC0 |
| Adobe Firefly (effets sonores) | À éviter | « Libres de droits pour un usage commercial », mais la redistribution des fichiers seuls n'est pas clarifiée |

Si l'utilisateur tient à ElevenLabs : lui expliquer l'exclusion ci-dessus, avec le lien vers les conditions,
et lui proposer Stable Audio Open. Ne pas générer de son ElevenLabs destiné à un pack, même privé : le
manifeste exige une licence (CC0, CC BY) que ces sons ne peuvent pas porter.

**Licence à déclarer** : `CC0-1.0`. Un son généré sans apport créatif humain n'est en général pas protégé
par le droit d'auteur (U.S. Copyright Office, rapport de janvier 2025) ; Creative Commons recommande CC0
dans ce cas. `author` : l'utilisateur ; `source` : modèle, invite et graine.

## 2. Préparer la machine

La génération se fait **sur la machine de l'utilisateur**, pas dans une session cloud : il faut une carte
graphique (CUDA) ou un Mac Apple Silicon, et Hugging Face doit être joignable. Sur processeur seul, compter
plusieurs minutes par extrait.

1. Accepter la licence du modèle sur https://huggingface.co/stabilityai/stable-audio-open-1.0 (modèle « gated »).
2. `pip install torch diffusers transformers accelerate torchsde numpy`
3. `hf auth login` : le jeton reste sur la machine de l'utilisateur. **Ne jamais** demander de jeton ou de
   clé dans la conversation, ni l'écrire dans un fichier du dépôt.

## 3. Écrire les invites

Le modèle comprend l'anglais. Une bonne invite décrit une **ambiance stable** : la source, la distance,
le lieu, la densité, la météo. Le script ajoute de lui-même « continuous ambient field recording, steady,
no music », et une invite négative (musique, voix, bips, évènement soudain, fondus, silence).

| Sorte de son | Exemple d'invite |
| --- | --- |
| Pluie | `steady medium rain on leaves and a wooden porch, close, no thunder` |
| Forêt | `temperate forest at dawn, distant songbirds, light breeze in the leaves` |
| Mer | `gentle waves on a pebble beach, close, regular rhythm` |
| Ville | `busy european street, distant traffic and footsteps, no horns` |
| Intérieur | `crackling fireplace in a quiet stone room, close` |
| Fantastique | `deep cave ambience, distant water drips, low wind hum, reverberant` |

- Préciser ce qu'il ne faut **pas** entendre quand c'est probable (« no thunder », « no horns », « no voices »).
- Pas de mots désignant un instant (« a door slams ») : chaque évènement marquant reviendrait à chaque tour de boucle.
- Proposer les invites à l'utilisateur et attendre son accord avant de lancer une génération longue.

## 4. Générer et choisir

```bash
python3 tools/generate_ambience.py "steady medium rain on leaves, close" --out sons/pluie --count 3
```

- Chaque extrait dure au plus 47 s ; le script note modèle, invite, graine et date dans `sons/pluie/generation.json`.
- Faire écouter les variantes à l'utilisateur ; il choisit. Rejeter un extrait qui contient de la musique,
  une voix, un évènement isolé marquant, un fondu ou un trou.
- Une variante presque bonne se retravaille en changeant la graine (`--seed`) ou en précisant l'invite.

## 5. Passer au post-traitement

Un extrait de 47 s donne une boucle de 40 s (le fondu de bouclage en consomme 3) :

```bash
python3 tools/prepare_loop.py sons/pluie/extrait-002.wav mon-pack/audio/pluie.ogg --duration 40
```

Pour 60 s, enchaîner deux extraits de la **même invite** (`--concat`) et faire écouter le passage de l'un
à l'autre. Continuer ensuite avec le skill `constituer-pack` (manifeste, mots-clés, vérification, ZIP), en
reportant dans `source` le modèle, l'invite et la graine relevés dans `generation.json`.
