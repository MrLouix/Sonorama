# Format des packs de sons

Un pack ajoute des sons à Sonorama. C'est une archive ZIP qui ne contient que des données, jamais de code.
Ce document décrit les versions **1** et **2** du format. La version 2 ajoute les empreintes des fichiers
audio et la signature ; la version 1 reste lisible. Le pack de base embarqué dans l'appli utilise le même
format (`app/src/main/assets/basepack`).

## Contenu de l'archive

```
mon-pack.zip
├── manifest.json      obligatoire, à la racine
├── manifest.sig       pack signé : signature du manifeste
├── signing-key.pub    pack signé : clé publique de l'auteur
├── LICENSES.md        recommandé : auteur, licence et source de chaque son
└── audio/
    ├── cheminee.ogg
    └── horloge.ogg
```

Seuls les fichiers déclarés dans le manifeste, et `LICENSES.md`, sont copiés ; tout le reste est ignoré.

## Manifeste

```json
{
  "format": 1,
  "id": "exemple.maison",
  "version": 1,
  "name": { "fr": "Maison", "en": "Home" },
  "pages": [
    { "id": "maison", "name": { "fr": "Maison", "en": "Home" }, "symbol": "home" }
  ],
  "sounds": [
    {
      "id": "cheminee",
      "file": "audio/cheminee.ogg",
      "name": { "fr": "Cheminée", "en": "Fireplace" },
      "symbol": "fireplace",
      "defaultVolume": 70,
      "page": "maison",
      "author": "Jeanne Martin",
      "license": "CC-BY-4.0",
      "source": "https://exemple.org/sons/cheminee",
      "keywords": { "fr": ["cheminée", "feu de bois", "bûches"], "en": ["fireplace", "logs"] }
    }
  ]
}
```

### Pack

| Champ | Obligatoire | Description |
| --- | --- | --- |
| `format` | oui | Version du format : `1` ou `2` |
| `id` | oui | Identifiant stable du pack. Minuscules, chiffres, `.`, `_`, `-` ; 64 caractères au plus ; commence par une lettre ou un chiffre. `sonorama.base` est réservé |
| `version` | oui | Entier ≥ 1. Une version supérieure met à jour le pack installé |
| `name` | oui | Nom affiché, en `fr` et/ou en `en` (60 caractères au plus) |
| `pages` | non | Pages intégrées à créer |
| `sounds` | oui | De 1 à 500 sons |

### Page

| Champ | Obligatoire | Description |
| --- | --- | --- |
| `id` | oui | Identifiant de la page, mêmes règles que pour le pack |
| `name` | oui | Nom de l'onglet |
| `symbol` | non | Pictogramme (voir plus bas) ; `music_note` par défaut ; 64 caractères au plus |

Un son peut aussi rejoindre une page déjà fournie par l'appli, sans la déclarer :
`nature`, `ville`, `feerique`. Si une page déclarée existe déjà, elle est complétée. Les anciennes pages `meteo`, `technologique` et `transports` restent acceptées : leurs sons rejoignent `nature` (`meteo`) ou `ville`.

### Son

| Champ | Obligatoire | Description |
| --- | --- | --- |
| `id` | oui | Identifiant du son, unique dans le pack. Le garder d'une version à l'autre conserve le volume réglé et la place de la carte sur les pages personnalisées |
| `file` | oui | Chemin relatif dans l'archive, en `.ogg`. Pas de `..`, pas de chemin absolu, pas d'antislash |
| `name` | oui | Nom de la carte, en `fr` et/ou en `en` |
| `symbol` | non | Pictogramme ; `music_note` par défaut ; 64 caractères au plus |
| `defaultVolume` | non | Volume initial de la carte, de 0 à 100 ; 70 par défaut |
| `page` | oui | Page qui reçoit la carte : déclarée dans `pages` ou fournie par l'appli |
| `author` | non | Auteur du son, affiché dans « Licences des sons » ; 200 caractères au plus |
| `license` | oui | Voir ci-dessous ; 64 caractères au plus |
| `source` | non | Adresse d'origine du son ; 500 caractères au plus |
| `sha256` | pack signé | Empreinte SHA-256 du fichier audio, en hexadécimal minuscule. Vérifiée à l'import dès qu'elle est présente |
| `keywords` | non | Mots-clés du mode vocal, par code de langue à deux lettres (`fr`, `en`) : 30 au plus par langue, 40 caractères au plus chacun. Voir ci-dessous |

### Mots-clés du mode vocal

Le mode vocal lance une carte quand il entend un de ses mots-clés. Le champ est facultatif ; les versions de l'appli antérieures au mode vocal l'ignorent, et il ne demande pas de nouveau numéro de format.

- Casse, accents et ponctuation sont ignorés ; un mot-clé peut compter plusieurs mots (`"feu de camp"`).
- Aucune variante n'est déduite : déclarer chaque forme utile (`"loup"`, `"loups"`).
- Éviter les homonymes (« mer » et « mère », « étang » et « étant ») et les mots à plusieurs sens (« feu », « vol ») : une expression (« bord de mer ») ne se déclenche pas par erreur.
- Un mot absent du vocabulaire du modèle Vosk ne sera jamais reconnu ; l'appli le grise dans l'écran des mots-clés. Les noms propres et les mots inventés sont les cas habituels.
- Les mots-clés font partie du manifeste : la signature d'un pack les couvre.
- Un utilisateur qui modifie les mots-clés d'une carte garde les siens à la mise à jour du pack.

Les champs inconnus sont ignorés, pour permettre des ajouts futurs.

## Audio

- Ogg Vorbis, 44,1 kHz, stéréo, environ 128 kbit/s.
- Boucles de 30 à 120 secondes dont la fin s'enchaîne avec le début sans raccord audible.
- Niveau normalisé d'un son à l'autre (le pack de base vise −17 LUFS, crête réelle ≤ −1,5 dBTP).
- 500 Mo au plus une fois décompressé.

Exemple d'encodage : `ffmpeg -i boucle.wav -c:a libvorbis -q:a 4 -ar 44100 -ac 2 audio/boucle.ogg`

## Licences acceptées

| Valeur de `license` | Acceptée |
| --- | --- |
| `CC0`, `CC0-1.0`, `Public-Domain` | oui |
| `CC-BY`, `CC-BY-SA`, avec version (`CC-BY-4.0`) et juridiction (`CC-BY-3.0-FR`) éventuelles | oui, avec `author` renseigné |
| Toute licence « pas d'usage commercial » ou « pas de modification » (`NC`, `ND`, `NonCommercial`, `NoDerivs`) | non |
| Toute autre valeur | non |

La liste est fermée : une valeur qui n'a pas exactement l'une de ces formes est refusée.

## Symboles

`symbol` prend le nom d'un pictogramme Material, par exemple `forest`, `water_drop`, `thunderstorm`, `train`,
`fireplace`, `castle`. La liste proposée par l'appli (environ 165 noms, classés par thème) se trouve dans
[`Symbols.kt`](../app/src/main/kotlin/io/github/mrlouix/sonorama/ui/Symbols.kt). Un nom inconnu est remplacé
par une note de musique.

## Ce que vérifie l'import

L'import refuse le pack, en indiquant la raison, si :

- le fichier n'est pas une archive ZIP, ou `manifest.json` est absent ou illisible ;
- `format` n'est pas une version connue ;
- un identifiant, un nom ou un chemin est invalide, ou un son est déclaré deux fois ;
- un son désigne une page inconnue ou porte une licence non acceptée ;
- un fichier audio déclaré manque, n'est pas un fichier Ogg, ou ne correspond pas à son `sha256` ;
- la signature est invalide, incomplète, ou ne couvre pas tous les fichiers audio ;
- le contenu dépasse 500 Mo.

Réimporter un pack déjà installé demande **toujours** confirmation, quelle que soit la version. Le dialogue
propose « Afficher l'empreinte » : l'empreinte SHA-256 du fichier ZIP, à comparer avec celle que publie
l'auteur. Les volumes, les pages personnalisées et les cartes composées sont conservés.

## Signature

Un pack ne contient que des données : la signature ne dit pas qu'il est « sûr », elle garantit **qui** l'a
produit. Elle empêche qu'un fichier se fasse passer pour la mise à jour d'un pack que vous avez déjà.

| Mention dans l'appli | Signification |
| --- | --- |
| Signé par l'équipe Sonorama | La clé est l'une des clés officielles compilées dans l'appli |
| Signé par son auteur | Signature valide, clé inconnue de l'appli |
| Non signé | Aucune signature ; le pack reste importable |

La clé d'un pack est **mémorisée au premier import**, et le reste après une désinstallation :

| Pack déjà connu | Fichier importé | Résultat |
| --- | --- | --- |
| Signé | Même clé | Confirmation habituelle |
| Signé | Autre clé | Confirmation avec avertissement |
| Signé, installé | Non signé | Refusé ; il faut d'abord supprimer le pack |
| Signé, désinstallé | Non signé | Confirmation avec avertissement |
| Non signé | Signé | Confirmation ; la clé est mémorisée |

### Signer un pack

Il faut Python 3.10+ et `openssl` (fourni avec Git pour Windows).

```bash
python3 tools/sign_pack.py keygen ma-cle.pem          # une seule fois
python3 tools/sign_pack.py sign mon-pack/ ma-cle.pem  # écrit mon-pack.zip
python3 tools/sign_pack.py fingerprint mon-pack.zip   # réaffiche les empreintes
```

`sign` complète le manifeste (format 2, `sha256` de chaque son), écrit `manifest.sig` et `signing-key.pub`,
fabrique le ZIP et affiche deux empreintes à publier à côté du lien de téléchargement : celle du ZIP et
celle de la clé.

**La clé privée (`ma-cle.pem`) ne doit jamais être publiée ni commitée** — le `.gitignore` du dépôt écarte
les fichiers `*.pem`. Gardez-en une sauvegarde : si vous la perdez, vos prochaines versions seront
signalées comme signées par une autre clé.

### Détail technique

- Algorithme : ECDSA sur la courbe P-256 avec SHA-256.
- `manifest.sig` : la signature (DER) des octets exacts de `manifest.json`, encodée en Base64.
- `signing-key.pub` : la clé publique au format PEM (`-----BEGIN PUBLIC KEY-----`).
- Empreinte d'une clé : SHA-256 de la clé publique encodée en DER.
- Dans un pack signé, chaque son porte un `sha256` : c'est ce qui étend la signature du manifeste aux
  fichiers audio.

À la main, sans le script :

```bash
openssl ecparam -name prime256v1 -genkey -noout -out ma-cle.pem
openssl pkey -in ma-cle.pem -pubout -out signing-key.pub
openssl dgst -sha256 -sign ma-cle.pem manifest.json | base64 -w0 > manifest.sig
```

### Clé officielle du projet

Pour que vos packs portent la mention « Signé par l'équipe Sonorama » :

1. Créez la clé sur votre machine : `python3 tools/sign_pack.py keygen sonorama-officielle.pem`.
   Conservez-la hors ligne ; elle ne doit transiter par aucun dépôt ni aucune session d'assistant.
2. Copiez l'empreinte affichée dans `OFFICIAL_KEYS`
   ([`PackSignature.kt`](../app/src/main/kotlin/io/github/mrlouix/sonorama/data/pack/PackSignature.kt)).
3. Publiez une version de l'appli : seules les versions qui contiennent l'empreinte reconnaissent la clé.

La liste accepte plusieurs empreintes, ce qui permet de préparer une clé de remplacement à l'avance.
**Tant qu'elle est vide, aucun pack n'est présenté comme officiel**, hormis le pack de base embarqué.

## Fabriquer l'archive

```bash
cd mon-pack
zip -r ../mon-pack.zip manifest.json LICENSES.md audio/
```

Copiez ensuite le ZIP dans le dossier Téléchargements du téléphone, puis dans l'appli : menu **Packs**,
**Importer un pack**.

## Fichiers de page

Une page personnalisée s'exporte en fichier `.sonorama-page.json` (appui long sur l'onglet, **Exporter**) et
se réimporte par le bouton **+**. Le fichier désigne les sons par leur identifiant complet (`pack/son`) :
les packs correspondants doivent être installés sur le téléphone qui importe.
