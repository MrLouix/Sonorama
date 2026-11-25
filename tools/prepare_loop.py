#!/usr/bin/env python3
"""Prépare une boucle d'ambiance pour un pack Sonorama, à partir de n'importe quel fichier audio.

Étapes, dans l'ordre :
  1. décodage par ffmpeg en 44,1 kHz stéréo (un son mono est dupliqué sur les deux canaux), graves
     inaudibles sur un téléphone coupés (60 Hz par défaut) ;
  2. retrait du silence en début et en fin ;
  3. choix du passage le plus stable, dont la fin et le début ont le même niveau (sauf --skip) ;
  4. bouclage par fondu enchaîné à puissance constante : la fin est fondue dans le début,
     si bien que le dernier échantillon enchaîne naturellement sur le premier ;
  5. recentrage de la stéréo au-delà de 2 dB d'écart entre les canaux ;
  6. normalisation au niveau du pack de base (−17 LUFS, crête réelle ≤ −1,5 dBTP, limiteur) ;
  7. encodage Ogg Vorbis (qualité 4, environ 128 kbit/s), sans métadonnées ;
  8. contrôle : saut au point de bouclage, écart de niveau entre la fin et le début.

Dépendances : Python 3.10+, numpy, ffmpeg (avec libvorbis).

Usage :
  python3 tools/prepare_loop.py source.wav sortie.ogg
  python3 tools/prepare_loop.py source.mp3 sortie.ogg --duration 90 --crossfade 4
  python3 tools/prepare_loop.py sortie.ogg --check          # contrôle seul d'une boucle existante
Plusieurs sources (--concat) sont mises bout à bout avant le bouclage, utile quand un générateur
limite la durée d'un extrait. Une source de moins de 33 s demande --repeat : la boucle est répétée jusqu'à 30 s.
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
LOUDNESS = -17.0  # LUFS intégrés (ITU-R BS.1770)
PEAK_DB = -1.5  # crête réelle (suréchantillonnée 4 fois)
MAX_LIMITING_DB = 8.0  # au-delà, le limiteur s'entendrait : on laisse le son plus bas
MAX_BALANCE_DB = 2.0
SILENCE_DB = -50.0


# ------------------------------------------------------------------------- entrée / sortie


def run(*args: str, data: bytes | None = None) -> bytes:
    try:
        return subprocess.run(args, input=data, capture_output=True, check=True).stdout
    except FileNotFoundError:
        sys.exit(f"Commande introuvable : {args[0]}. Installez ffmpeg et ajoutez-le au PATH.")
    except subprocess.CalledProcessError as error:
        sys.exit(f"{args[0]} a échoué : {error.stderr.decode(errors='replace').strip()[-400:]}")


def decode(path: Path, highpass: float = 0.0) -> np.ndarray:
    """Fichier audio quelconque → tableau (2, n) en flottants, 44,1 kHz stéréo."""
    filters = ["-af", f"highpass=f={highpass:g}"] if highpass > 0 else []
    raw = run("ffmpeg", "-v", "error", "-i", str(path), *filters, "-f", "f32le", "-acodec", "pcm_f32le",
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
            "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-serial_offset", "1", str(path))
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


def balance(stereo: np.ndarray) -> np.ndarray:
    """Recentre la stéréo quand un canal est nettement plus fort que l'autre."""
    levels = [db(np.sqrt(np.mean(channel**2))) for channel in stereo]
    gap = levels[0] - levels[1]
    if abs(gap) <= MAX_BALANCE_DB:
        return stereo
    gains = 10 ** (np.array([[-gap / 2], [gap / 2]]) / 20)
    return stereo * gains


# -------------------------------------------------------------------------------- niveau perçu


def k_weight(stereo: np.ndarray) -> np.ndarray:
    """Pondération K (BS.1770), appliquée en fréquence : la boucle est périodique, sans effet de bord."""
    n = stereo.shape[1]
    z = np.exp(-2j * np.pi * np.fft.rfftfreq(n))  # z⁻¹
    k = np.tan(np.pi * 1681.974450955533 / SR)
    q, vh = 0.7071752369554196, 10 ** (3.999843853973347 / 20)
    vb = vh**0.4996667741545416
    a0 = 1 + k / q + k * k
    shelf = ((vh + vb * k / q + k * k) + 2 * (k * k - vh) * z + (vh - vb * k / q + k * k) * z**2) / (
        a0 + 2 * (k * k - 1) * z + (1 - k / q + k * k) * z**2)
    k = np.tan(np.pi * 38.13547087602444 / SR)
    q = 0.5003270373238773
    a0 = 1 + k / q + k * k
    rumble = (1 - 2 * z + z**2) * a0 / (a0 + 2 * (k * k - 1) * z + (1 - k / q + k * k) * z**2)
    return np.fft.irfft(np.fft.rfft(stereo, axis=1) * shelf * rumble, n, axis=1)


def loudness(stereo: np.ndarray) -> float:
    """Niveau intégré en LUFS, avec les deux seuils de BS.1770 (blocs de 400 ms, recouvrement de 75 %)."""
    weighted = k_weight(stereo)
    power = np.concatenate([[0.0], np.cumsum(np.sum(weighted**2, axis=0))])
    block, hop = int(0.4 * SR), int(0.1 * SR)
    starts = np.arange(0, stereo.shape[1] - block + 1, hop)
    blocks = (power[starts + block] - power[starts]) / block
    blocks = blocks[blocks > 10 ** ((-70 + 0.691) / 10)]
    if blocks.size == 0:
        return -70.0
    gate = blocks.mean() * 0.1  # −10 LU sous la moyenne
    return float(-0.691 + 10 * np.log10(blocks[blocks > gate].mean()))


def oversampled_peaks(stereo: np.ndarray) -> np.ndarray:
    """Crête réelle autour de chaque échantillon, tous canaux confondus (suréchantillonnage 4 fois)."""
    n = stereo.shape[1]
    spectrum = np.fft.rfft(stereo, axis=1)
    if n % 2 == 0:
        spectrum[:, -1] *= 0.5
    padded = np.zeros((2, 2 * n + 1), dtype=complex)
    padded[:, : spectrum.shape[1]] = spectrum
    fine = np.abs(np.fft.irfft(padded, 4 * n, axis=1) * 4).max(axis=0)
    return fine.reshape(n, 4).max(axis=1)


def true_peak(stereo: np.ndarray) -> float:
    return db(float(oversampled_peaks(stereo).max()))


def limit_peaks(stereo: np.ndarray, limit_db: float) -> tuple[np.ndarray, float]:
    """Limiteur à anticipation (5 ms), périodique comme la boucle. Renvoie aussi la réduction maximale en dB."""
    need = np.minimum(1.0, 10 ** (limit_db / 20) / np.maximum(oversampled_peaks(stereo), 1e-9))
    if need.min() >= 1.0:
        return stereo, 0.0
    half = int(0.005 * SR)
    padded = np.concatenate([need[-2 * half :], need, need[: 2 * half]])
    # Minimum glissant puis lissage par une fenêtre de même largeur : le gain ne dépasse jamais le besoin.
    windows = np.lib.stride_tricks.sliding_window_view(padded, 2 * half + 1)
    held = np.concatenate([chunk.min(axis=1) for chunk in np.array_split(windows, 64)])
    kernel = np.hanning(2 * half + 3)[1:-1]
    gain = np.convolve(held, kernel / kernel.sum(), mode="valid")
    return stereo * gain, -db(float(need.min()))


def normalize(stereo: np.ndarray, target: float = LOUDNESS, limit_db: float = PEAK_DB - 0.5, limiting: float = MAX_LIMITING_DB) -> np.ndarray:
    """Amène la boucle au niveau perçu du pack. La marge de 0,5 dB absorbe les dépassements de l'encodeur.

    Un son fait de pics isolés (gouttes, carillons) ne peut pas monter sans écraser ses pics : on s'arrête
    alors à `limiting` dB de réduction, et le son reste sous la cible.
    """
    stereo = stereo - stereo.mean(axis=1, keepdims=True)
    gain = target - loudness(stereo)
    gain = min(gain, limit_db - true_peak(stereo) + limiting)
    out, _ = limit_peaks(stereo * 10 ** (gain / 20), limit_db)
    for _ in range(4):
        missing = target - loudness(out)
        headroom = limit_db + limiting - true_peak(stereo) - gain
        step = min(missing, headroom)
        if abs(step) < 0.1:
            break
        gain += step
        out, _ = limit_peaks(stereo * 10 ** (gain / 20), limit_db)
    return out


# ------------------------------------------------------------------------------ choix du passage


def best_window(stereo: np.ndarray, target: float, crossfade: float) -> tuple[float, float]:
    """(début, durée) du passage le plus stable dont la fin raccorde au début, par pas de 0,5 s."""
    hop = SR // 2
    count = stereo.shape[1] // hop
    weighted = k_weight(stereo[:, : count * hop])
    power = np.mean(weighted.reshape(2, count, hop) ** 2, axis=(0, 2))
    level = 10 * np.log10(power + 1e-10)
    fade = max(1, int(round(crossfade * 2)))
    first = np.concatenate([[0.0], np.cumsum(level)])
    second = np.concatenate([[0.0], np.cumsum(level**2)])
    top = min(int(target * 2), count - fade)
    # Au moins les trois quarts de la durée visée, quand la source le permet.
    shortest = max(MIN_DURATION, 0.75 * target)
    best = (np.inf, 0.0, top / 2)
    for length in range(top, min(top, int(shortest * 2)) - 1, -1):
        starts = np.arange(0, count - length - fade + 1)
        ends = starts + length
        size = length + fade
        mean = (first[starts + size] - first[starts]) / size
        spread = np.sqrt(np.maximum((second[starts + size] - second[starts]) / size - mean**2, 0))
        head = (first[starts + fade] - first[starts]) / fade
        tail = (first[ends + fade] - first[ends]) / fade
        seam = np.abs(level[ends] - level[ends - 1])
        # Un écart de niveau au raccord coûte plus cher que quelques secondes de moins.
        score = spread + np.abs(head - tail) + 0.7 * seam + (top - length) / 2 * 0.15
        index = int(np.argmin(score))
        if score[index] < best[0] - 1e-9:
            best = (float(score[index]), starts[index] / 2, length / 2)
    return best[1], best[2]


# ---------------------------------------------------------------------------------- contrôle


def check(stereo: np.ndarray, label: str) -> bool:
    """Affiche les mesures de la boucle ; renvoie faux si un défaut est probable."""
    duration = stereo.shape[1] / SR
    level = loudness(stereo)
    peak = true_peak(stereo)
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
    print(f"  durée {duration:.1f} s · {level:.1f} LUFS · crête réelle {peak:.1f} dBTP")
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
    if abs(level - LOUDNESS) > 1.5:
        print(f"  ⚠ niveau perçu éloigné de {LOUDNESS:.0f} LUFS : le son tranchera avec les autres")
        ok = False
    if peak > PEAK_DB + 0.3:
        print("  ⚠ crête trop haute")
        ok = False
    if ok:
        print("  ✓ prête pour un pack")
    return ok


def build(
    sources: list[Path], target: float, crossfade: float, highpass: float, skip: float | None, repeat: bool,
    limiting: float = MAX_LIMITING_DB,
) -> tuple[np.ndarray, float, float, int]:
    """Sources → boucle normalisée, début et durée du passage retenu, nombre de répétitions."""
    parts = [trim_silence(decode(path, highpass)) for path in sources]
    stereo = parts[0]
    joint = SR  # fondu de 1 s entre deux extraits
    for part in parts[1:]:
        t = np.linspace(0, np.pi / 2, joint)
        blend = stereo[:, -joint:] * np.cos(t) + part[:, :joint] * np.sin(t)
        stereo = np.concatenate([stereo[:, :-joint], blend, part[:, joint:]], axis=1)

    available = stereo.shape[1] / SR
    duration = target
    if skip is None:
        if available < MIN_DURATION + crossfade:
            if not repeat:
                sys.exit(
                    f"Source trop courte : {available:.1f} s disponibles, {MIN_DURATION + crossfade:.1f} s nécessaires. "
                    f"Assemblez plusieurs extraits (--concat), ou répétez la boucle (--repeat)."
                )
            skip, duration = 0.0, available - crossfade
        else:
            skip, duration = best_window(stereo, target, crossfade)
    elif available < skip + duration + crossfade:
        if available < skip + duration + crossfade - 0.05:
            sys.exit(f"Source trop courte : {available:.1f} s disponibles, {skip + duration + crossfade:.1f} s nécessaires.")
        duration = min(duration, available - skip - crossfade)
    start = int(skip * SR)
    loop = make_loop(stereo[:, start : start + int((duration + crossfade) * SR)], crossfade)
    times = 1
    while repeat and loop.shape[1] * times < MIN_DURATION * SR:
        times += 1
    loop = normalize(balance(loop), limiting=limiting)
    return np.tile(loop, (1, times)), skip, duration, times


def write(loop: np.ndarray, out: Path, quality: float) -> int:
    """Encode la boucle ; l'encodeur peut dépasser la crête visée, on baisse alors le plafond du limiteur."""
    ceiling = PEAK_DB - 0.5
    for _ in range(4):
        encode(loop, out, quality)
        excess = true_peak(decode(out)) - PEAK_DB
        if excess <= 0:
            break
        ceiling -= excess + 0.1
        loop, _ = limit_peaks(loop, ceiling)
    return out.stat().st_size // 1024


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", type=Path, nargs="+", help="fichier(s) audio source, ou la boucle à contrôler avec --check")
    parser.add_argument("--out", type=Path, help="fichier .ogg produit (sinon : dernier argument positionnel)")
    parser.add_argument("--duration", type=float, default=60.0, help="durée de la boucle en secondes (30 à 120 ; 60 par défaut)")
    parser.add_argument("--crossfade", type=float, default=3.0, help="fondu de bouclage en secondes (3 par défaut)")
    parser.add_argument("--quality", type=float, default=4.0, help="qualité Vorbis (4 ≈ 128 kbit/s)")
    parser.add_argument("--skip", type=float, help="début du passage, en secondes (sinon : choisi automatiquement)")
    parser.add_argument("--highpass", type=float, default=60.0, help="coupe-bas en Hz (60 par défaut, 0 pour aucun)")
    parser.add_argument("--limiting", type=float, default=MAX_LIMITING_DB, help="réduction maximale du limiteur en dB (8 par défaut)")
    parser.add_argument("--repeat", action="store_true", help="source courte : répéter la boucle jusqu'à 30 s")
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

    loop, skip, duration, times = build(sources, args.duration, args.crossfade, args.highpass, args.skip, args.repeat, args.limiting)
    size = write(loop, out, args.quality)
    print(f"Boucle écrite : {out} ({size} Ko) · passage de {skip:.1f} à {skip + duration:.1f} s" + (f" · répétée {times} fois" if times > 1 else ""))
    # Contrôle sur le fichier encodé, tel que l'appli le lira.
    sys.exit(0 if check(decode(out), str(out)) else 1)


if __name__ == "__main__":
    main()
