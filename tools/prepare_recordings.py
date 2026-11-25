#!/usr/bin/env python3
"""Refait les boucles enregistrées du pack de base à partir des sources Freesound.

La recette (tools/recordings.json) donne pour chaque son le numéro Freesound, l'auteur et les réglages :
  skip, duration   passage retenu, en secondes ; absents : choisis automatiquement, puis écrits par --freeze
  crossfade, highpass, limiting, repeat   comme dans prepare_loop.py
  accepted         boucle écoutée et acceptée : elle entre dans le pack de base
Les sources ne sont pas versionnées : les télécharger dans pack-sources/ sous leur nom Freesound
(« 531015__noted451__ocean-waves.wav »). Un son sans source est signalé et laissé tel quel.

Dépendances : Python 3.10+, numpy, ffmpeg (avec libvorbis).
Usage :
  python3 tools/prepare_recordings.py                                      # refait dans le pack les sons acceptés
  python3 tools/prepare_recordings.py --out pack-sources/boucles            # essai, sans toucher au pack
  python3 tools/prepare_recordings.py --only waves --out pack-sources/boucles --freeze
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import prepare_loop

ROOT = Path(__file__).resolve().parent.parent
RECIPE = ROOT / "tools" / "recordings.json"
SOURCES = ROOT / "pack-sources"
BASE_PACK = ROOT / "app" / "src" / "main" / "assets" / "basepack" / "audio"
QUALITY = 4.0


def load() -> list[dict]:
    return json.loads(RECIPE.read_text(encoding="utf-8"))


def save(recipe: list[dict]) -> None:
    lines = ",\n".join("  " + json.dumps(entry, ensure_ascii=False) for entry in recipe)
    RECIPE.write_text(f"[\n{lines}\n]\n", encoding="utf-8", newline="\n")


def source_of(entry: dict) -> Path | None:
    return next(iter(sorted(SOURCES.glob(f"{entry['freesound']}__*"))), None)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, help="dossier des boucles produites (sinon : le pack de base, sons acceptés seulement)")
    parser.add_argument("--only", action="append", help="ne refaire que ce son (répétable)")
    parser.add_argument("--freeze", action="store_true", help="écrire dans la recette les passages choisis")
    args = parser.parse_args()

    recipe = load()
    missing, warned = [], []
    for entry in recipe:
        if args.only and entry["id"] not in args.only:
            continue
        if args.out is None and not entry.get("accepted"):
            continue
        source = source_of(entry)
        if source is None:
            missing.append(entry)
            continue
        loop, skip, duration, _ = prepare_loop.build(
            [source], entry.get("duration", 60.0), entry.get("crossfade", 3.0), entry.get("highpass", 60.0),
            entry.get("skip"), entry.get("repeat", False), entry.get("limiting", prepare_loop.MAX_LIMITING_DB),
        )
        out = (args.out or BASE_PACK) / f"{entry['id']}.ogg"
        size = prepare_loop.write(loop, out, QUALITY)
        print(f"passage de {skip:.1f} à {skip + duration:.1f} s · {size} Ko")
        if not prepare_loop.check(prepare_loop.decode(out), entry["id"]):
            warned.append(entry["id"])
        if args.freeze:
            entry["skip"], entry["duration"] = round(skip, 2), round(duration, 2)
    if args.freeze:
        save(recipe)
    if warned:
        print("À écouter en priorité : " + ", ".join(warned))
    for entry in missing:
        print(f"Source absente : {entry['id']} (https://freesound.org/s/{entry['freesound']}/)")


if __name__ == "__main__":
    main()
