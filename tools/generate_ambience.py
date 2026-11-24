#!/usr/bin/env python3
"""Génère des extraits d'ambiance avec Stable Audio Open, sur votre machine, pour un pack Sonorama.

Les extraits sont des WAV bruts : ils passent ensuite par tools/prepare_loop.py (bouclage, niveau, Ogg).
Chaque génération est consignée dans `generation.json` à côté des WAV (modèle, invite, graine, date),
pour pouvoir la refaire et pour remplir le champ `source` du manifeste.

Licence : Stable Audio Open est distribué sous la Stability AI Community License. Relisez la version en
vigueur avant de publier (https://stability.ai/license) : elle fixe notamment un plafond de chiffre
d'affaires au-delà duquel une licence entreprise est demandée. Le modèle est « gated » sur Hugging Face :
acceptez sa licence sur https://huggingface.co/stabilityai/stable-audio-open-1.0, puis `hf auth login`.

Dépendances : Python 3.10+, numpy, et
  pip install torch diffusers transformers accelerate torchsde
Une carte graphique (CUDA) ou un Mac Apple Silicon (MPS) est fortement conseillé ; sur processeur seul,
compter plusieurs minutes par extrait.

Usage :
  python3 tools/generate_ambience.py "steady rain on a tin roof, close" --out sons/pluie-toit --count 3
  python3 tools/generate_ambience.py "night forest, crickets, light wind" --out sons/foret --seconds 45 --seed 7
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import wave
from pathlib import Path

import numpy as np

MODEL = "stabilityai/stable-audio-open-1.0"
# Le modèle produit au plus 47 s environ par extrait.
MAX_SECONDS = 47.0
# Ce qui gâche une boucle d'ambiance : musique, voix, évènements isolés qui se répètent à chaque tour.
STYLE = "continuous ambient field recording, steady, natural stereo, no music, high quality"
NEGATIVE = (
    "music, melody, rhythm, drums, speech, voice, singing, beeps, alarm, sudden loud event, "
    "fade in, fade out, silence, low quality, distortion, clipping, mono"
)


def write_wav(samples: np.ndarray, rate: int, path: Path) -> None:
    """samples : (canaux, n) en flottants."""
    pcm = (np.clip(samples.T, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(samples.shape[0])
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(pcm.tobytes())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("prompt", help="description du son, en anglais (le modèle a été entraîné en anglais)")
    parser.add_argument("--out", type=Path, required=True, help="dossier des extraits produits")
    parser.add_argument("--seconds", type=float, default=MAX_SECONDS, help=f"durée de chaque extrait (≤ {MAX_SECONDS:.0f} s)")
    parser.add_argument("--count", type=int, default=2, help="nombre de variantes à produire (2 par défaut)")
    parser.add_argument("--seed", type=int, default=0, help="graine de départ ; chaque variante prend la suivante")
    parser.add_argument("--steps", type=int, default=150, help="étapes de diffusion (qualité contre temps ; 150 par défaut)")
    parser.add_argument("--no-style", action="store_true", help="ne pas ajouter les consignes d'ambiance à l'invite")
    args = parser.parse_args()

    if not 5 <= args.seconds <= MAX_SECONDS:
        sys.exit(f"--seconds doit rester entre 5 et {MAX_SECONDS:.0f}.")
    try:
        import torch  # noqa: PLC0415
        from diffusers import StableAudioPipeline  # noqa: PLC0415
    except ImportError:
        sys.exit("Installez d'abord : pip install torch diffusers transformers accelerate torchsde")

    if torch.cuda.is_available():
        device, dtype = "cuda", torch.float16
    elif torch.backends.mps.is_available():
        device, dtype = "mps", torch.float32
    else:
        device, dtype = "cpu", torch.float32
        print("Aucune carte graphique détectée : la génération sera lente.")

    prompt = args.prompt if args.no_style else f"{args.prompt}, {STYLE}"
    pipe = StableAudioPipeline.from_pretrained(MODEL, torch_dtype=dtype).to(device)
    rate = pipe.vae.sampling_rate
    args.out.mkdir(parents=True, exist_ok=True)
    log_path = args.out / "generation.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.is_file() else []

    for index in range(args.count):
        seed = args.seed + index
        generator = torch.Generator("cpu").manual_seed(seed)
        audio = pipe(
            prompt,
            negative_prompt=NEGATIVE,
            num_inference_steps=args.steps,
            audio_end_in_s=args.seconds,
            num_waveforms_per_prompt=1,
            generator=generator,
        ).audios[0]
        samples = audio.float().cpu().numpy()
        path = args.out / f"extrait-{seed:03d}.wav"
        write_wav(samples, rate, path)
        log.append({
            "file": path.name,
            "model": MODEL,
            "prompt": prompt,
            "negative_prompt": NEGATIVE,
            "seed": seed,
            "seconds": args.seconds,
            "steps": args.steps,
            "date": dt.datetime.now().isoformat(timespec="seconds"),
        })
        print(f"{path} ({samples.shape[1] / rate:.1f} s, graine {seed})")
    log_path.write_text(json.dumps(log, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Écoutez les variantes, gardez la meilleure, puis : python3 tools/prepare_loop.py <extrait> <son>.ogg")


if __name__ == "__main__":
    main()
