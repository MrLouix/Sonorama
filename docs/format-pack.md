# Format des packs de sons

Un pack ajoute des sons à Sonorama. C'est une archive ZIP qui ne contient que des données, jamais de code.
Ce document décrit la version **1** du format ; le pack de base embarqué dans l'appli l'utilise aussi
(`app/src/main/assets/basepack`).

## Contenu de l'archive

```
mon-pack.zip
├── manifest.json      obligatoire, à la racine
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
      "source": "https://exemple.org/sons/cheminee"
    }
  ]
}
```

### Pack

| Champ | Obligatoire | Description |
| --- | --- | --- |
| `format` | oui | Version du format : `1` |
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
| `symbol` | non | Pictogramme (voir plus bas) ; `music_note` par défaut |

Un son peut aussi rejoindre une page déjà fournie par l'appli, sans la déclarer :
`nature`, `ville`, `technologique`, `meteo`, `transports`. Si une page déclarée existe déjà, elle est complétée.

### Son

| Champ | Obligatoire | Description |
| --- | --- | --- |
| `id` | oui | Identifiant du son, unique dans le pack. Le garder d'une version à l'autre conserve le volume réglé et la place de la carte sur les pages personnalisées |
| `file` | oui | Chemin relatif dans l'archive, en `.ogg`. Pas de `..`, pas de chemin absolu, pas d'antislash |
| `name` | oui | Nom de la carte, en `fr` et/ou en `en` |
| `symbol` | non | Pictogramme ; `music_note` par défaut |
| `defaultVolume` | non | Volume initial de la carte, de 0 à 100 ; 70 par défaut |
| `page` | oui | Page qui reçoit la carte : déclarée dans `pages` ou fournie par l'appli |
| `author` | non | Auteur du son, affiché dans « Licences des sons » |
| `license` | oui | Voir ci-dessous |
| `source` | non | Adresse d'origine du son |

Les champs inconnus sont ignorés, pour permettre des ajouts futurs.

## Audio

- Ogg Vorbis, 44,1 kHz, stéréo, environ 128 kbit/s.
- Boucles de 30 à 120 secondes dont la fin s'enchaîne avec le début sans raccord audible.
- Niveau normalisé d'un son à l'autre (le pack de base vise −24 dBFS RMS).
- 500 Mo au plus une fois décompressé.

Exemple d'encodage : `ffmpeg -i boucle.wav -c:a libvorbis -q:a 4 -ar 44100 -ac 2 audio/boucle.ogg`

## Licences acceptées

| Valeur de `license` | Acceptée |
| --- | --- |
| `CC0-1.0`, `CC0`, `Public-Domain` | oui |
| `CC-BY-4.0` et autres `CC-BY-…`, `CC-BY-SA-…` | oui, avec `author` renseigné |
| Toute licence contenant `NC` (pas d'usage commercial) ou `ND` (pas de modification) | non |
| Toute autre valeur | non |

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
- un fichier audio déclaré manque ou n'est pas un fichier Ogg ;
- le contenu dépasse 500 Mo.

Réimporter une version supérieure met le pack à jour ; réimporter la même version, ou une plus ancienne,
propose de remplacer ou d'ignorer. Les volumes, les pages personnalisées et les cartes composées sont conservés.

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
