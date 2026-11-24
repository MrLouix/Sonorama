#!/usr/bin/env python3
"""Vérifie un dossier de pack Sonorama avant de le publier, et fabrique son ZIP non signé.

Reprend les règles de l'import de l'appli (app/src/main/kotlin/.../data/pack/PackManifest.kt) et ajoute
des contrôles que l'appli ne fait pas mais qui comptent pour la qualité :
  * chaque fichier est un Ogg Vorbis 44,1 kHz stéréo de 30 à 120 s (via ffprobe) ;
  * le symbole existe dans l'appli (sinon la carte affiche une note de musique) ;
  * avec --vocab, chaque mot-clé existe dans le vocabulaire du modèle Vosk de sa langue.
Pour vérifier aussi les raccords de boucle : tools/prepare_loop.py --check dossier/audio/*.ogg

Dépendances : Python 3.10+, ffprobe (fourni avec ffmpeg). Pour --vocab : `pip install vosk` et les
modèles décompressés (https://alphacephei.com/vosk/models).

Usage :
  python3 tools/check_pack.py mon-pack/
  python3 tools/check_pack.py mon-pack/ --vocab fr=vosk-model-small-fr-0.22 --vocab en=vosk-model-small-en-us-0.15
  python3 tools/check_pack.py mon-pack/ --zip            # écrit mon-pack.zip (non signé) si tout est bon
  python3 tools/check_pack.py app/src/main/assets/basepack --base-pack
Pour un pack signé, utiliser ensuite tools/sign_pack.py sign mon-pack/ ma-cle.pem.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SYMBOLS_KT = ROOT / "app/src/main/kotlin/io/github/mrlouix/sonorama/ui/Symbols.kt"

# Mêmes valeurs que PackFormat (PackManifest.kt).
VERSIONS = {1, 2}
ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
SHA256 = re.compile(r"[0-9a-f]{64}")
LANGUAGE = re.compile(r"[a-z]{2}")
LICENSE = re.compile(r"CC0(-1\.0)?|PUBLIC-DOMAIN|CC-BY(-SA)?(-[1-4]\.[0-9](-[A-Z]{2,3})?)?")
RESTRICTED = re.compile(r"(^|-)(NC|ND|NONCOMMERCIAL|NODERIVS|NODERIVATIVES)(-|$)")
BUILT_IN_PAGES = {"nature", "ville", "technologique", "meteo", "transports"}
MAX_SOUNDS = 500
MAX_NAME = 60
MAX_SYMBOL = 64
MAX_AUTHOR = 200
MAX_LICENSE = 64
MAX_SOURCE = 500
MAX_KEYWORDS = 30
MAX_KEYWORD = 40
MAX_UNCOMPRESSED = 500 * 1024 * 1024
RESERVED_ID = "sonorama.base"

ALLOW_RESERVED = False
errors: list[str] = []
warnings: list[str] = []


def error(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def is_safe_path(path: str) -> bool:
    if not path or len(path) > 200 or path.startswith("/") or "\\" in path or ":" in path:
        return False
    if any(ord(c) < 0x20 for c in path):
        return False
    return all(part not in ("", ".", "..") for part in path.split("/"))


def license_allowed(license: str) -> bool:
    value = license.strip().upper().replace(" ", "-")
    return not RESTRICTED.search(value) and LICENSE.fullmatch(value) is not None


def check_name(name: object, owner: str) -> None:
    if not isinstance(name, dict) or not (name.get("fr", "").strip() or name.get("en", "").strip()):
        error(f"{owner} : nom manquant (fr et/ou en)")
        return
    for lang in ("fr", "en"):
        if len(name.get(lang, "")) > MAX_NAME:
            error(f"{owner} : nom {lang} de plus de {MAX_NAME} caractères")
        if not name.get(lang, "").strip():
            warn(f"{owner} : pas de nom en {lang} (l'autre langue sera affichée)")


def known_symbols() -> set[str]:
    if not SYMBOLS_KT.is_file():
        return set()
    return set(re.findall(r'"([a-z0-9_]+)" to Icons\.', SYMBOLS_KT.read_text(encoding="utf-8")))


def probe(path: Path) -> dict | None:
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
             "stream=codec_name,sample_rate,channels:format=duration,format_name", "-of", "json", str(path)],
            capture_output=True, check=True,
        ).stdout
    except FileNotFoundError:
        sys.exit("ffprobe est introuvable : installez ffmpeg.")
    except subprocess.CalledProcessError:
        return None
    data = json.loads(out)
    stream = (data.get("streams") or [{}])[0]
    return {
        "codec": stream.get("codec_name"),
        "rate": int(stream.get("sample_rate", 0) or 0),
        "channels": stream.get("channels"),
        "duration": float(data.get("format", {}).get("duration", 0) or 0),
        "container": data.get("format", {}).get("format_name", ""),
    }


def check_audio(folder: Path, file: str, owner: str) -> int:
    path = folder / file
    if not path.is_file():
        error(f"{owner} : fichier absent {file}")
        return 0
    info = probe(path)
    if info is None or info["container"] != "ogg" or info["codec"] != "vorbis":
        error(f"{owner} : {file} n'est pas un Ogg Vorbis lisible")
        return path.stat().st_size
    if info["rate"] != 44100:
        warn(f"{owner} : {file} est en {info['rate']} Hz (44 100 attendus)")
    if info["channels"] != 2:
        warn(f"{owner} : {file} a {info['channels']} canal(aux) (stéréo attendue)")
    if not 30 <= info["duration"] <= 120:
        warn(f"{owner} : {file} dure {info['duration']:.1f} s (boucles de 30 à 120 s attendues)")
    return path.stat().st_size


def load_vocab(specs: list[str]) -> dict[str, object]:
    models: dict[str, object] = {}
    if not specs:
        return models
    try:
        import vosk  # noqa: PLC0415
    except ImportError:
        sys.exit("--vocab demande le module vosk : pip install vosk")
    vosk.SetLogLevel(-1)
    for spec in specs:
        lang, _, path = spec.partition("=")
        if not LANGUAGE.fullmatch(lang) or not Path(path).is_dir():
            sys.exit(f"--vocab {spec} : attendu langue=dossier-du-modèle")
        models[lang] = vosk.Model(path)
    return models


def unknown_words(model: object, phrase: str) -> list[str]:
    """Comme l'appli : une forme élidée connue (« d'été ») suffit, sinon chaque partie doit l'être."""
    missing = []
    for word in phrase.lower().split():
        if model.vosk_model_find_word(word) >= 0:
            continue
        parts = [p for p in re.split(r"['’]", word) if p]
        if any(model.vosk_model_find_word(p) < 0 for p in parts):
            missing.append(word)
    return missing


def check(folder: Path, vocab: dict[str, object]) -> dict | None:
    manifest_path = folder / "manifest.json"
    if not manifest_path.is_file():
        error("manifest.json introuvable")
        return None
    raw = manifest_path.read_bytes()
    if len(raw) > 1024 * 1024:
        error("manifest.json dépasse 1 Mo")
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        error(f"manifest.json illisible : {e}")
        return None

    if manifest.get("format") not in VERSIONS:
        error(f"format {manifest.get('format')} inconnu (1 ou 2)")
    pack_id = manifest.get("id", "")
    if not isinstance(pack_id, str) or not ID.fullmatch(pack_id):
        error(f"identifiant de pack invalide : {pack_id!r}")
    if pack_id == RESERVED_ID and not ALLOW_RESERVED:
        error(f"l'identifiant {RESERVED_ID} est réservé au pack de base")
    if not isinstance(manifest.get("version"), int) or manifest["version"] < 1:
        error("version : entier ≥ 1 attendu")
    check_name(manifest.get("name"), "pack")

    symbols = known_symbols()
    pages = set()
    for page in manifest.get("pages", []):
        pid = page.get("id", "")
        if not ID.fullmatch(pid):
            error(f"page {pid!r} : identifiant invalide")
        check_name(page.get("name"), f"page {pid}")
        symbol = page.get("symbol", "music_note")
        if len(symbol) > MAX_SYMBOL:
            error(f"page {pid} : symbole trop long")
        elif symbols and symbol not in symbols:
            warn(f"page {pid} : symbole « {symbol} » inconnu de l'appli")
        pages.add(pid)

    sounds = manifest.get("sounds", [])
    if not sounds:
        error("aucun son déclaré")
    if len(sounds) > MAX_SOUNDS:
        error(f"plus de {MAX_SOUNDS} sons")
    ids: set[str] = set()
    files: set[str] = set()
    total = 0
    for sound in sounds:
        sid = sound.get("id", "")
        owner = f"son {sid}"
        if not ID.fullmatch(sid):
            error(f"{owner} : identifiant invalide")
        if sid in ids:
            error(f"{owner} : identifiant en double")
        ids.add(sid)
        check_name(sound.get("name"), owner)
        file = sound.get("file", "")
        if not is_safe_path(file):
            error(f"{owner} : chemin dangereux ou invalide {file!r}")
            continue
        if not file.lower().endswith(".ogg"):
            error(f"{owner} : {file} n'est pas un .ogg")
        if file in files:
            error(f"{owner} : fichier {file} déjà utilisé")
        files.add(file)
        page = sound.get("page", "")
        if page not in pages and page not in BUILT_IN_PAGES:
            error(f"{owner} : page {page!r} ni déclarée ni intégrée à l'appli")
        license = sound.get("license", "")
        if len(license) > MAX_LICENSE or not license_allowed(license):
            error(f"{owner} : licence « {license} » refusée (CC0, domaine public, CC BY, CC BY-SA seulement)")
        if license.upper().startswith("CC-BY") and not sound.get("author", "").strip():
            error(f"{owner} : une licence CC BY demande un auteur à citer")
        for field, limit in (("symbol", MAX_SYMBOL), ("author", MAX_AUTHOR), ("source", MAX_SOURCE)):
            if len(sound.get(field, "")) > limit:
                error(f"{owner} : champ {field} de plus de {limit} caractères")
        symbol = sound.get("symbol", "music_note")
        if symbols and symbol not in symbols:
            warn(f"{owner} : symbole « {symbol} » inconnu de l'appli")
        volume = sound.get("defaultVolume", 70)
        if not isinstance(volume, int) or not 0 <= volume <= 100:
            warn(f"{owner} : defaultVolume hors de 0–100 (ramené dans la plage)")
        if "sha256" in sound and not SHA256.fullmatch(str(sound["sha256"])):
            error(f"{owner} : sha256 mal formé")
        keywords = sound.get("keywords", {})
        if not keywords:
            warn(f"{owner} : aucun mot-clé (le mode vocal ne le lancera pas)")
        for lang, words in keywords.items():
            if not LANGUAGE.fullmatch(lang) or not isinstance(words, list) or len(words) > MAX_KEYWORDS:
                error(f"{owner} : mots-clés {lang!r} invalides (code à 2 lettres, {MAX_KEYWORDS} au plus)")
                continue
            for word in words:
                if not isinstance(word, str) or len(word) > MAX_KEYWORD:
                    error(f"{owner} : mot-clé {word!r} trop long ({MAX_KEYWORD} caractères au plus)")
                elif lang in vocab and (missing := unknown_words(vocab[lang], word)):
                    warn(f"{owner} : mot-clé « {word} » inconnu du modèle {lang} ({', '.join(missing)}) : jamais reconnu")
        total += check_audio(folder, file, owner)

    if total > MAX_UNCOMPRESSED:
        error("plus de 500 Mo de sons")
    if not (folder / "LICENSES.md").is_file():
        warn("LICENSES.md absent : il est attendu dans l'archive")
    extra = {p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file()} - files - {
        "manifest.json", "LICENSES.md", "manifest.sig", "signing-key.pub"}
    if extra:
        warn(f"fichiers non déclarés, ignorés par l'import : {', '.join(sorted(extra)[:10])}")
    return manifest


def write_zip(folder: Path, manifest: dict) -> Path:
    out = folder.resolve().with_suffix(".zip")
    names = ["manifest.json"] + (["LICENSES.md"] if (folder / "LICENSES.md").is_file() else [])
    names += [s["file"] for s in manifest["sounds"]]
    # Stocké sans compression : l'Ogg est déjà compressé.
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as archive:
        for name in names:
            archive.write(folder / name, name)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("folder", type=Path, help="dossier du pack (manifest.json, audio/, LICENSES.md)")
    parser.add_argument("--vocab", action="append", default=[], help="langue=dossier d'un modèle Vosk décompressé")
    parser.add_argument("--zip", action="store_true", help="écrire le ZIP non signé si aucune erreur")
    parser.add_argument("--base-pack", action="store_true", help=f"autoriser l'identifiant {RESERVED_ID} (pack de base de l'appli)")
    args = parser.parse_args()
    global ALLOW_RESERVED
    ALLOW_RESERVED = args.base_pack

    manifest = check(args.folder, load_vocab(args.vocab))
    for message in warnings:
        print(f"⚠ {message}")
    for message in errors:
        print(f"✗ {message}")
    if errors or manifest is None:
        print(f"{len(errors)} erreur(s) : l'appli refuserait ce pack.")
        sys.exit(1)
    print(f"✓ {len(manifest['sounds'])} son(s), aucune erreur ; {len(warnings)} avertissement(s).")
    if args.zip:
        out = write_zip(args.folder, manifest)
        print(f"ZIP écrit : {out}")


if __name__ == "__main__":
    main()
