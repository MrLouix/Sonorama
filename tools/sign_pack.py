#!/usr/bin/env python3
"""Signe un pack de sons Sonorama (format 2).

La signature est une ECDSA P-256 / SHA-256 des octets de `manifest.json`. Le manifeste portant
l'empreinte SHA-256 de chaque fichier audio, elle couvre tout le pack.

Dépendances : Python 3.10+ et la commande `openssl` (fournie avec Git pour Windows).

Usage :
  python3 tools/sign_pack.py keygen ma-cle.pem          crée une clé privée — à garder secrète, hors du dépôt
  python3 tools/sign_pack.py sign mon-pack/ ma-cle.pem  écrit mon-pack.zip, signé
  python3 tools/sign_pack.py fingerprint mon-pack.zip   affiche l'empreinte du ZIP et celle de la clé
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

MANIFEST = "manifest.json"
SIGNATURE = "manifest.sig"
SIGNING_KEY = "signing-key.pub"
LICENSES = "LICENSES.md"


def openssl(*args: str, data: bytes | None = None) -> bytes:
    try:
        return subprocess.run(["openssl", *args], input=data, capture_output=True, check=True).stdout
    except FileNotFoundError:
        sys.exit("La commande openssl est introuvable. Sous Windows, elle est fournie avec Git pour Windows.")
    except subprocess.CalledProcessError as error:
        sys.exit(f"openssl a échoué : {error.stderr.decode(errors='replace').strip()}")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def grouped(hex_digest: str) -> str:
    return " ".join(hex_digest[i : i + 4] for i in range(0, len(hex_digest), 4))


def key_fingerprint_from_pem(public_pem: bytes) -> str:
    """Empreinte d'une clé : SHA-256 de la clé publique encodée en DER, comme dans l'appli."""
    return sha256(openssl("pkey", "-pubin", "-outform", "DER", data=public_pem))


def keygen(args: argparse.Namespace) -> None:
    path = Path(args.key)
    if path.exists():
        sys.exit(f"{path} existe déjà : une clé ne s'écrase pas.")
    openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(path))
    public_pem = openssl("pkey", "-in", str(path), "-pubout")
    print(f"Clé privée écrite dans {path}. Gardez-la secrète et sauvegardée, hors de tout dépôt.")
    print(f"Empreinte de la clé : {key_fingerprint_from_pem(public_pem)}")


def sign(args: argparse.Namespace) -> None:
    folder = Path(args.folder)
    manifest_path = folder / MANIFEST
    if not manifest_path.is_file():
        sys.exit(f"{manifest_path} est introuvable.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    files = []
    for sound in manifest["sounds"]:
        audio = folder / sound["file"]
        if not audio.is_file():
            sys.exit(f"Fichier audio déclaré mais absent : {sound['file']}")
        sound["sha256"] = sha256(audio.read_bytes())
        files.append(sound["file"])
    manifest["format"] = 2

    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    manifest_path.write_bytes(manifest_bytes)

    public_pem = openssl("pkey", "-in", args.key, "-pubout")
    signature = openssl("dgst", "-sha256", "-sign", args.key, data=manifest_bytes)
    (folder / SIGNING_KEY).write_bytes(public_pem)
    (folder / SIGNATURE).write_text(base64.b64encode(signature).decode() + "\n", encoding="ascii")

    out = Path(args.out) if args.out else folder.resolve().with_suffix(".zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as archive:
        for name in [MANIFEST, SIGNATURE, SIGNING_KEY, *([LICENSES] if (folder / LICENSES).is_file() else []), *files]:
            archive.write(folder / name, name)

    print(f"Pack signé : {out}")
    print(f"Empreinte du ZIP    : {grouped(sha256(out.read_bytes()))}")
    print(f"Empreinte de la clé : {grouped(key_fingerprint_from_pem(public_pem))}")
    print("Publiez ces deux empreintes à côté du lien de téléchargement.")


def fingerprint(args: argparse.Namespace) -> None:
    path = Path(args.zip)
    print(f"Empreinte du ZIP    : {grouped(sha256(path.read_bytes()))}")
    with zipfile.ZipFile(path) as archive:
        if SIGNING_KEY in archive.namelist():
            print(f"Empreinte de la clé : {grouped(key_fingerprint_from_pem(archive.read(SIGNING_KEY)))}")
        else:
            print("Ce pack n'est pas signé.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(required=True)

    command = commands.add_parser("keygen", help="crée une clé privée de signature")
    command.add_argument("key", help="fichier de clé privée à créer (PEM)")
    command.set_defaults(run=keygen)

    command = commands.add_parser("sign", help="signe un dossier de pack et fabrique le ZIP")
    command.add_argument("folder", help="dossier contenant manifest.json et les fichiers audio")
    command.add_argument("key", help="clé privée (PEM)")
    command.add_argument("--out", help="chemin du ZIP produit (par défaut : le nom du dossier)")
    command.set_defaults(run=sign)

    command = commands.add_parser("fingerprint", help="affiche les empreintes d'un pack")
    command.add_argument("zip", help="fichier ZIP du pack")
    command.set_defaults(run=fingerprint)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
