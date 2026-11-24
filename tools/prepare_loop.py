#!/usr/bin/env python3
"""Prépare une boucle d'ambiance pour un pack Sonorama, à partir de n'importe quel fichier audio.

Étapes, dans l'ordre :
  1. décodage par ffmpeg en 44,1 kHz stéréo (un son mono est dupliqué sur les deux canaux) ;
  2. retrait du silence en début et en fin, puis de la composante continue ;
  3. coupe à la durée visée (60 s par défaut, entre 30 et 120 s) ;
  4. bouclage par fondu enchaîné à puissance constante : la fin est fondue dans le début,
     si bien que le dernier échantillon enchaîne naturellement sur le premier ;
  5. normalisation au niveau du pack de base (RMS −24 dBFS, crête ≤ −1,5 dBFS, limiteur doux) ;
  6. encodage Ogg Vorbis (qualité 4, environ 128 kbit/s), sans métadonnées ;
  7. contrôle : saut au point de bouclage, écart de niveau entre la fin et le début.

Dépendances : Python 3.10+, numpy, ffmpeg (avec libvorbis).

Usage :
  python3 tools/prepare_loop.py source.wav sortie.ogg
  python3 tools/prepare_loop.py source.mp3 sortie.ogg --duration 90 --crossfade 4
  python3 tools/prepare_loop.py sortie.ogg --check          # contrôle seul d'une boucle existante
Plusieurs sources (--concat) sont mises bout à bout avant le bouclage, utile quand un générateur
limite la durée d'un extrait.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100
MIN_DURATION = 30.0
MAX_DURATION = 120.0
RMS_DB = -24.0
PEAK_DB = -1.5
SILENCE_DB = -50.0


# ------------------------------------------------------------------------- entrée / sortie


def run(*args: str, data: bytes | None = None) -> bytes:
    try:
        return subprocess.run(args, input=data, capture_output=True, check=True).stdout
    except FileNotFoundError:
        sys.exit(f"Commande introuvable : {args[0]}. Installez ffmpeg et ajoutez-le au PATH.")
    except subprocess.CalledProcessError as error:
        sys.exit(f"{args[0]} a échoué : {error.stderr.decode(errors='replace').strip()[-400:]}")


def decode(path: Path) -> np.ndarray:
    """Fichier audio quelconque → tableau (2, n) en flottants, 44,1 kHz stéréo."""
    raw = run("ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-acodec", "pcm_f32le",
              "-ac", "2", "-ar", str(SR), "-")
    samples = np.frombuffer(raw, dtype="<f4")
    if samples.size < SR * 2:
        sys.exit(f"{path} : moins d'une seconde de son exploitable.")
    return samples.reshape(-1, 2).T.astype(np.float64)


def encode(stereo: np.ndarray, path: Path, quality: float) -> None:
    pcm = (np.clip(stereo.T, -1, 1) * 32767).astype("<i2")
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
        wav_path = Path(handle.name)
    try:
        with wave.open(str(wav_path), "wb") as wav:
            wav.setnchannels(2)
            wav.setsampwidth(2)
            wav.setframerate(SR)
            wav.writeframes(pcm.tobytes())
        path.parent.mkdir(parents=True, exist_ok=True)
        run("ffmpeg", "-y", "-v", "error", "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", str(quality),
            "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(path))
    finally:
        wav_path.unlink(missing_ok=True)


# ------------------------------------------------------------------------------ traitements


def db(value: float) -> float:
    return 20 * np.log10(max(value, 1e-12))


def trim_silence(stereo: np.ndarray) -> np.ndarray:
    """Retire le silence en tête et en queue (fenêtres de 50 ms sous −50 dBFS)."""
    window = SR // 20
    frames = stereo.shape[1] // window
    levels = np.sqrt(np.mean(stereo[:, : frames * window].reshape(2, frames, window) ** 2, axis=(0, 2)))
    loud = np.nonzero(levels > 10 ** (SILENCE_DB / 20))[0]
    if loud.size == 0:
        sys.exit("La source est silencieuse.")
    return stereo[:, loud[0] * window : (loud[-1] + 1) * window]


def make_loop(stereo: np.ndarray, crossfade: float) -> np.ndarray:
    """Fond les `crossfade` dernières secondes dans le début : la boucle obtenue est plus courte d'autant.

    Après le bouclage, la fin de la boucle (échantillon n−X−1 de la source) enchaîne sur son début,
    qui commence par l'échantillon n−X de la source : la continuité est celle de l'enregistrement.
    Puissance constante (cos/sin), adaptée aux ambiances dont la fin et le début ne sont pas corrélés.
    """
    x = int(crossfade * SR)
    n = stereo.shape[1]
    if n <= 2 * x:
        sys.exit("Source trop courte pour ce fondu de bouclage.")
    t = np.linspace(0, np.pi / 2, x)
    loop = stereo[:, : n - x].copy()
    loop[:, :x] = stereo[:, :x] * np.sin(t) + stereo[:, n - x :] * np.cos(t)
    return loop


def normalize(stereo: np.ndarray) -> np.ndarray:
    """Même réglage que le pack de base (tools/generate_base_pack.py, fonction finish)."""
    stereo = stereo - stereo.mean(axis=1, keepdims=True)
    rms = np.sqrt(np.mean(stereo**2)) + 1e-12
    stereo = stereo * (10 ** (RMS_DB / 20) / rms)
    crest = np.abs(stereo).max()
    limit = 10 ** (PEAK_DB / 20)
    if crest > limit:
        stereo = np.tanh(stereo / limit * 0.9) * limit / np.tanh(0.9 * crest / limit) * (crest / limit)
        stereo *= limit / np.abs(stereo).max()
    return stereo


# ---------------------------------------------------------------------------------- contrôle


def check(stereo: np.ndarray, label: str) -> bool:
    """Affiche les mesures de la boucle ; renvoie faux si un défaut est probable."""
    duration = stereo.shape[1] / SR
    rms = db(np.sqrt(np.mean(stereo**2)))
    peak = db(np.abs(stereo).max())
    # Saut au raccord, comparé aux écarts habituels d'un échantillon au suivant.
    steps = np.abs(np.diff(stereo, axis=1))
    jump = np.abs(stereo[:, 0] - stereo[:, -1]).max()
    typical = np.percentile(steps.max(axis=0), 99.9)
    # Niveau des 500 ms de part et d'autre du raccord.
    half = SR // 2
    tail = db(np.sqrt(np.mean(stereo[:, -half:] ** 2)))
    head = db(np.sqrt(np.mean(stereo[:, :half] ** 2)))
    ok = True
    print(f"{label}")
    print(f"  durée {duration:.1f} s · RMS {rms:.1f} dBFS · crête {peak:.1f} dBFS")
    print(f"  raccord : saut {jump:.4f} (99,9 % des pas ≤ {typical:.4f}) · niveau fin {tail:.1f} / début {head:.1f} dB")
    if not MIN_DURATION <= duration <= MAX_DURATION:
        print(f"  ⚠ durée hors de 30–120 s")
        ok = False
    if jump > max(typical * 1.5, 0.02):
        print("  ⚠ saut au raccord : un clic est probable")
        ok = False
    if abs(tail - head) > 3:
        print("  ⚠ le niveau change de plus de 3 dB au raccord")
        ok = False
    if abs(rms - RMS_DB) > 1.5:
        print(f"  ⚠ niveau moyen éloigné de {RMS_DB:.0f} dBFS : le son tranchera avec les autres")
        ok = False
    if peak > PEAK_DB + 0.3:
        print("  ⚠ crête trop haute")
        ok = False
    if ok:
        print("  ✓ prête pour un pack")
    return ok


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", type=Path, nargs="+", help="fichier(s) audio source, ou la boucle à contrôler avec --check")
    parser.add_argument("--out", type=Path, help="fichier .ogg produit (sinon : dernier argument positionnel)")
    parser.add_argument("--duration", type=float, default=60.0, help="durée de la boucle en secondes (30 à 120 ; 60 par défaut)")
    parser.add_argument("--crossfade", type=float, default=3.0, help="fondu de bouclage en secondes (3 par défaut)")
    parser.add_argument("--quality", type=float, default=4.0, help="qualité Vorbis (4 ≈ 128 kbit/s)")
    parser.add_argument("--skip", type=float, default=0.0, help="secondes à ignorer au début de la source")
    parser.add_argument("--concat", action="store_true", help="mettre les sources bout à bout, avec un fondu de 1 s entre elles")
    parser.add_argument("--check", action="store_true", help="contrôler une boucle existante sans rien produire")
    args = parser.parse_args()

    if args.check:
        ok = all(check(decode(path), str(path)) for path in args.source)
        sys.exit(0 if ok else 1)

    sources = list(args.source)
    out = args.out
    if out is None:
        if len(sources) < 2:
            sys.exit("Indiquez le fichier produit : prepare_loop.py source sortie.ogg")
        out = sources.pop()
    if out.suffix.lower() != ".ogg":
        sys.exit("Le fichier produit doit être un .ogg (seul format accepté par les packs).")
    if not MIN_DURATION <= args.duration <= MAX_DURATION:
        sys.exit("La durée doit rester entre 30 et 120 s.")
    if len(sources) > 1 and not args.concat:
        sys.exit("Plusieurs sources : ajoutez --concat pour les mettre bout à bout.")

    parts = [trim_silence(decode(path)) for path in sources]
    stereo = parts[0]
    joint = SR  # fondu de 1 s entre deux extraits
    for part in parts[1:]:
        t = np.linspace(0, np.pi / 2, joint)
        blend = stereo[:, -joint:] * np.cos(t) + part[:, :joint] * np.sin(t)
        stereo = np.concatenate([stereo[:, :-joint], blend, part[:, joint:]], axis=1)
    stereo = stereo[:, int(args.skip * SR) :]

    needed = int((args.duration + args.crossfade) * SR)
    available = stereo.shape[1] / SR
    if stereo.shape[1] < needed:
        sys.exit(
            f"Source trop courte : {available:.1f} s disponibles, {args.duration + args.crossfade:.1f} s nécessaires "
            f"(durée + fondu). Réduisez --duration, ou générez plusieurs extraits et utilisez --concat."
        )
    stereo = stereo[:, :needed]
    loop = normalize(make_loop(stereo, args.crossfade))
    encode(loop, out, args.quality)
    size = out.stat().st_size // 1024
    print(f"Boucle écrite : {out} ({size} Ko)")
    # Contrôle sur le fichier encodé, tel que l'appli le lira.
    sys.exit(0 if check(decode(out), str(out)) else 1)


if __name__ == "__main__":
    main()
