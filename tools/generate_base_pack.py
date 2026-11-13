#!/usr/bin/env python3
"""Génère le pack de base de Sonorama : 24 boucles d'ambiance synthétisées.

Chaque boucle est construite pour être périodique sur sa durée exacte :
  * les bruits sont filtrés dans le domaine fréquentiel (convolution circulaire) ;
  * les modulations lentes n'utilisent que des fréquences multiples de 1/durée ;
  * les évènements (gouttes, chants, crépitements) sont posés modulo la durée.
Le raccord est donc inaudible par construction, sans fondu de bouclage.

Les sons produits sont originaux et placés sous CC0 1.0.

Dépendances : Python 3.10+, numpy, ffmpeg (avec libvorbis).
Usage : python3 tools/generate_base_pack.py [--only nom] [--out dossier]
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100
DURATION = 30.0
N = int(SR * DURATION)
T = np.arange(N) / SR
FREQS = np.fft.rfftfreq(N, 1 / SR)
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "basepack"


# --------------------------------------------------------------------------- outils


def rng_for(name: str) -> np.random.Generator:
    return np.random.default_rng(abs(hash_name(name)))


def hash_name(name: str) -> int:
    value = 2166136261
    for byte in name.encode():
        value = ((value ^ byte) * 16777619) & 0xFFFFFFFF
    return value


def band(lo: float, hi: float, slope: float = 0.0, soft: float = 0.15) -> np.ndarray:
    """Gabarit fréquentiel : passe-bande à flancs doux, pente spectrale `slope` (dB/octave)."""
    f = np.maximum(FREQS, 1.0)
    shape = np.ones_like(f)
    if lo > 0:
        shape *= 1 / (1 + (lo / f) ** (2 / soft * 0.6))
    if hi < SR / 2:
        shape *= 1 / (1 + (f / hi) ** (2 / soft * 0.6))
    if slope:
        shape *= (f / 1000.0) ** (slope / 6.02)
    shape[0] = 0
    return shape


def peak(center: float, width: float) -> np.ndarray:
    """Résonance gaussienne (en octaves) autour de `center`."""
    f = np.maximum(FREQS, 1.0)
    return np.exp(-0.5 * (np.log2(f / center) / width) ** 2)


def noise(rng: np.random.Generator, shape: np.ndarray) -> np.ndarray:
    """Bruit périodique dont le spectre suit `shape`, normalisé en RMS."""
    spectrum = np.fft.rfft(rng.standard_normal(N)) * shape
    out = np.fft.irfft(spectrum, N)
    return out / (np.sqrt(np.mean(out**2)) + 1e-12)


def stereo_noise(rng: np.random.Generator, shape: np.ndarray, width: float = 0.7) -> np.ndarray:
    """Deux canaux partiellement décorrélés (width = 0 : mono, 1 : indépendants)."""
    mid = noise(rng, shape)
    left = noise(rng, shape)
    right = noise(rng, shape)
    a = np.sqrt(1 - width)
    b = np.sqrt(width)
    return np.stack([a * mid + b * left, a * mid + b * right])


def lfo(rng: np.random.Generator, max_cycles: int, min_cycles: int = 1, power: float = 1.0) -> np.ndarray:
    """Modulation lente périodique dans [0, 1], somme de sinusoïdes à nombre entier de cycles."""
    out = np.zeros(N)
    for k in range(min_cycles, max_cycles + 1):
        out += rng.uniform(0.4, 1.0) / k**power * np.sin(2 * np.pi * k * T / DURATION + rng.uniform(0, 2 * np.pi))
    out -= out.min()
    return out / (out.max() + 1e-12)


def tone(freq: float, phase: float = 0.0) -> np.ndarray:
    """Sinusoïde dont la fréquence est arrondie à un nombre entier de cycles par boucle."""
    cycles = max(1, round(freq * DURATION))
    return np.sin(2 * np.pi * cycles * T / DURATION + phase)


def pan(mono: np.ndarray, position: float) -> np.ndarray:
    """Panoramique à puissance constante ; position de -1 (gauche) à 1 (droite)."""
    angle = (position + 1) * np.pi / 4
    return np.stack([mono * np.cos(angle), mono * np.sin(angle)])


def add_at(buffer: np.ndarray, grain: np.ndarray, start: int) -> None:
    """Ajoute `grain` (mono ou stéréo) dans `buffer` stéréo à partir de `start`, modulo la durée."""
    if grain.ndim == 1:
        grain = np.stack([grain, grain])
    length = grain.shape[1]
    index = (start + np.arange(length)) % N
    buffer[:, index] += grain


def hann(length: int) -> np.ndarray:
    return np.hanning(max(length, 2))


def decay(length: int, tau: float) -> np.ndarray:
    return np.exp(-np.arange(length) / (tau * SR))


def circular_smooth(signal: np.ndarray, cutoff: float) -> np.ndarray:
    spectrum = np.fft.rfft(signal) * (1 / (1 + (FREQS / cutoff) ** 4))
    return np.fft.irfft(spectrum, N)


def circular_filter(signal: np.ndarray, shape: np.ndarray) -> np.ndarray:
    return np.fft.irfft(np.fft.rfft(signal, axis=-1) * shape, N, axis=-1)


def finish(stereo: np.ndarray, rms_db: float = -24.0, peak_db: float = -1.5) -> np.ndarray:
    """Normalise le niveau moyen (égalise les sons entre eux) en respectant une crête maximale."""
    stereo = stereo - stereo.mean(axis=1, keepdims=True)
    rms = np.sqrt(np.mean(stereo**2)) + 1e-12
    stereo = stereo * (10 ** (rms_db / 20) / rms)
    crest = np.abs(stereo).max()
    limit = 10 ** (peak_db / 20)
    if crest > limit:
        # Limiteur doux : préserve le niveau moyen autant que possible.
        stereo = np.tanh(stereo / limit * 0.9) * limit / np.tanh(0.9 * crest / limit) * (crest / limit)
        stereo *= limit / np.abs(stereo).max()
    return stereo


# ----------------------------------------------------------------------------- nature


def birds(rng):
    out = 0.05 * stereo_noise(rng, band(300, 9000, -4))
    species = [(2600, 5200, 3, 7), (3400, 6500, 2, 5), (1800, 3200, 2, 4), (4200, 7800, 4, 9)]
    for _ in range(75):
        lo, hi, min_notes, max_notes = species[rng.integers(len(species))]
        position = rng.uniform(-0.9, 0.9)
        level = rng.uniform(0.15, 1.0) ** 2
        start = rng.integers(N)
        base = rng.uniform(lo, hi)
        for _ in range(rng.integers(min_notes, max_notes + 1)):
            length = int(rng.uniform(0.035, 0.13) * SR)
            t = np.arange(length) / SR
            glide = rng.uniform(-0.35, 0.45)
            freq = base * (1 + glide * t / t[-1]) * (1 + 0.03 * np.sin(2 * np.pi * rng.uniform(25, 60) * t))
            chirp = np.sin(2 * np.pi * np.cumsum(freq) / SR) * hann(length) ** 1.5
            chirp += 0.25 * np.sin(4 * np.pi * np.cumsum(freq) / SR) * hann(length) ** 2
            add_at(out, pan(level * chirp, position), start)
            start += length + int(rng.uniform(0.01, 0.09) * SR)
            base *= rng.uniform(0.85, 1.2)
    return out


def stream(rng):
    flow = stereo_noise(rng, band(250, 5000, -3) * (1 + 1.5 * peak(900, 0.8)), 0.9)
    flow *= 0.75 + 0.25 * lfo(rng, 24, 6)
    out = 0.55 * flow
    for _ in range(2600):
        length = int(rng.uniform(0.008, 0.045) * SR)
        t = np.arange(length) / SR
        f0 = rng.uniform(450, 2600)
        freq = f0 * (1 + rng.uniform(0.2, 0.9) * t / t[-1])
        bubble = np.sin(2 * np.pi * np.cumsum(freq) / SR) * hann(length) * rng.uniform(0.05, 0.5)
        add_at(out, pan(bubble, rng.uniform(-1, 1)), rng.integers(N))
    return out


def waves(rng):
    cycles = 4
    phase = (T / DURATION * cycles) % 1.0
    surge = np.where(phase < 0.28, np.sin(np.pi * phase / 0.56) ** 2, np.exp(-(phase - 0.28) * 3.4))
    surge = circular_smooth(surge * (0.8 + 0.4 * lfo(rng, 3)), 2.0)
    surge = np.clip(surge, 0, None)
    body = stereo_noise(rng, band(60, 2500, -5), 0.6)
    foam = stereo_noise(rng, band(1500, 11000, -2), 0.95)
    shifted = np.roll(surge, int(0.5 * SR))
    return body * (0.25 + surge) + 0.45 * foam * (0.08 + shifted**1.5)


def campfire(rng):
    roar = stereo_noise(rng, band(30, 500, -7), 0.5) * (0.6 + 0.4 * lfo(rng, 40, 8))
    hiss = stereo_noise(rng, band(2000, 9000, -3), 0.9) * (0.5 + 0.5 * lfo(rng, 30, 5))
    out = 0.6 * roar + 0.08 * hiss
    activity = lfo(rng, 12, 2) ** 2
    count = 0
    while count < 1500:
        start = rng.integers(N)
        if rng.uniform() > 0.15 + 0.85 * activity[start]:
            continue
        count += 1
        length = int(rng.uniform(0.0008, 0.007) * SR)
        crackle = rng.standard_normal(length) * decay(length, rng.uniform(0.0004, 0.002))
        level = rng.uniform(0.1, 1.0) ** 3 * 2.2
        add_at(out, pan(level * crackle, rng.uniform(-0.7, 0.7)), start)
    for _ in range(26):
        length = int(rng.uniform(0.02, 0.05) * SR)
        t = np.arange(length) / SR
        pop = np.sin(2 * np.pi * rng.uniform(180, 420) * t) * decay(length, 0.008) + 0.5 * rng.standard_normal(length) * decay(length, 0.003)
        add_at(out, pan(1.6 * pop, rng.uniform(-0.5, 0.5)), rng.integers(N))
    return circular_filter(out, band(25, 12000))


def crickets(rng):
    out = 0.07 * stereo_noise(rng, band(150, 6000, -6))
    for _ in range(9):
        carrier = rng.uniform(3900, 5600)
        chirp_rate = rng.uniform(1.6, 3.2)
        chirps = max(1, round(chirp_rate * DURATION))
        pulse_rate = rng.uniform(42, 64)
        local = (T * chirps / DURATION + rng.uniform()) % 1.0
        duty = rng.uniform(0.25, 0.5)
        gate = np.where(local < duty, np.sqrt(np.clip(np.sin(np.pi * local / duty), 0, None)), 0.0)
        pulses = np.maximum(0, np.sin(2 * np.pi * pulse_rate * local * DURATION / chirps)) ** 2
        voice = tone(carrier, rng.uniform(0, 6)) * gate * pulses
        voice = voice * (0.4 + 0.6 * lfo(rng, 3))
        out += pan(rng.uniform(0.25, 1.0) * voice, rng.uniform(-1, 1))
    drone = tone(6800) * (0.6 + 0.4 * np.sin(2 * np.pi * round(11 * DURATION) * T / DURATION))
    out += 0.05 * np.stack([drone, np.roll(drone, 37)])
    return out


# ------------------------------------------------------------------------------ ville


def passes(rng, count, shape, min_len=3.0, max_len=6.0):
    out = np.zeros((2, N))
    for _ in range(count):
        length = int(rng.uniform(min_len, max_len) * SR)
        x = np.linspace(-1, 1, length)
        envelope = np.exp(-(x * 2.6) ** 2)
        grain_rng = np.random.default_rng(rng.integers(1 << 31))
        raw = grain_rng.standard_normal(length)
        spectrum = np.fft.rfft(raw)
        f = np.fft.rfftfreq(length, 1 / SR)
        raw = np.fft.irfft(spectrum * np.interp(f, FREQS, shape), length)
        raw /= np.sqrt(np.mean(raw**2)) + 1e-12
        direction = rng.choice([-1, 1])
        angle = (direction * x * 0.9 + 1) * np.pi / 4
        level = rng.uniform(0.4, 1.0)
        add_at(out, np.stack([raw * envelope * np.cos(angle), raw * envelope * np.sin(angle)]) * level, rng.integers(N))
    return out


def traffic(rng):
    rumble = stereo_noise(rng, band(25, 220, -6), 0.4) * (0.7 + 0.3 * lfo(rng, 10))
    wash = stereo_noise(rng, band(200, 2500, -5), 0.8) * (0.5 + 0.5 * lfo(rng, 8))
    out = 0.7 * rumble + 0.22 * wash
    out += 0.9 * passes(rng, 11, band(90, 2800, -4) * (1 + peak(700, 0.9)))
    return out


def crowd(rng):
    out = np.zeros((2, N))
    formants = [(520, 1250, 2500), (680, 1100, 2600), (420, 1900, 2700), (350, 900, 2400), (600, 1500, 2550)]
    for _ in range(34):
        f0 = rng.uniform(95, 240)
        wander = 1 + 0.12 * (lfo(rng, 45, 8) - 0.5)
        freq = f0 * wander
        cycles = np.sum(freq) / SR
        freq *= round(cycles) / cycles
        phase = np.cumsum(freq) / SR
        source = 2 * (phase % 1.0) - 1
        f1, f2, f3 = formants[rng.integers(len(formants))]
        shape = (peak(f1, 0.22) + 0.6 * peak(f2, 0.2) + 0.25 * peak(f3, 0.18)) * band(120, 3800)
        voice = circular_filter(source, shape)
        syllables = circular_smooth(np.abs(noise(rng, band(0.8, 6.5))), 9.0)
        talking = np.clip(circular_smooth(noise(rng, band(0.05, 0.5)), 1.0) * 1.4 + 0.25, 0, 1)
        voice *= np.clip(syllables - 0.35, 0, None) * talking
        voice /= np.sqrt(np.mean(voice**2)) + 1e-12
        out += pan(rng.uniform(0.3, 1.0) * voice, rng.uniform(-1, 1))
    out = circular_filter(out, band(150, 3200, -3))
    out /= np.sqrt(np.mean(out**2))
    room = stereo_noise(rng, band(100, 1500, -6), 0.8)
    return out + 0.22 * room


def fountain(rng):
    spray = stereo_noise(rng, band(700, 11000, -1.5) * (1 + peak(2500, 0.9)), 0.95)
    spray *= 0.85 + 0.15 * lfo(rng, 60, 20)
    basin = stereo_noise(rng, band(120, 900, -4), 0.6) * (0.7 + 0.3 * lfo(rng, 30, 6))
    out = 0.6 * spray + 0.3 * basin
    for _ in range(1500):
        length = int(rng.uniform(0.006, 0.03) * SR)
        t = np.arange(length) / SR
        f0 = rng.uniform(900, 4200)
        drop = np.sin(2 * np.pi * np.cumsum(f0 * (1 + 0.5 * t / t[-1])) / SR) * hann(length)
        add_at(out, pan(rng.uniform(0.05, 0.4) * drop, rng.uniform(-1, 1)), rng.integers(N))
    return out


def pigeons(rng):
    out = 0.16 * stereo_noise(rng, band(60, 1800, -6), 0.8) * (0.7 + 0.3 * lfo(rng, 6))
    for bird in range(4):
        position = rng.uniform(-0.8, 0.8)
        base = rng.uniform(330, 470)
        level = rng.uniform(0.5, 1.0)
        for _ in range(rng.integers(5, 8)):
            start = rng.integers(N)
            for index, duration in enumerate([0.32, 0.26, 0.62][: rng.integers(2, 4)]):
                length = int(duration * rng.uniform(0.85, 1.2) * SR)
                t = np.arange(length) / SR
                bend = 1 + 0.10 * np.sin(np.pi * t / t[-1]) - 0.05 * t / t[-1]
                freq = base * (1.0 if index != 1 else 1.08) * bend
                phase = 2 * np.pi * np.cumsum(freq) / SR
                purr = 0.72 + 0.28 * np.sin(2 * np.pi * rng.uniform(22, 30) * t)
                note = (np.sin(phase) + 0.22 * np.sin(2 * phase) + 0.07 * np.sin(3 * phase)) * purr
                note *= hann(length) ** 0.7
                add_at(out, pan(level * note, position), start)
                start += length + int(0.09 * SR)
    return out


def city_night(rng):
    hum = stereo_noise(rng, band(35, 180, -5), 0.3) * (0.8 + 0.2 * lfo(rng, 5))
    air = stereo_noise(rng, band(300, 5000, -6), 0.9) * (0.6 + 0.4 * lfo(rng, 4))
    mains = tone(100) + 0.4 * tone(200) + 0.15 * tone(300)
    out = 0.7 * hum + 0.14 * air + 0.05 * np.stack([mains, mains])
    out += 0.5 * passes(rng, 4, band(60, 900, -6), 6.0, 10.0)
    return out


# ---------------------------------------------------------------------- technologique


def server_room(rng):
    fans = stereo_noise(rng, band(120, 7000, -3) * (1 + 0.8 * peak(450, 0.7) + 0.5 * peak(2300, 0.5)), 0.9)
    out = 0.8 * fans * (0.93 + 0.07 * lfo(rng, 12, 3))
    for freq, level in [(120, 0.10), (240, 0.06), (361, 0.03), (1180, 0.012), (5400, 0.006), (5460, 0.005), (7200, 0.004)]:
        wave_ = tone(freq, rng.uniform(0, 6))
        out += level * 4 * pan(wave_, rng.uniform(-0.6, 0.6))
    return out


def fan(rng):
    blade = 31.0
    cycles = round(blade * DURATION)
    chop = 0.5 + 0.5 * np.sin(2 * np.pi * cycles * T / DURATION)
    chop = 0.72 + 0.28 * chop**2
    air = stereo_noise(rng, band(80, 3200, -4.5) * (1 + peak(260, 0.8)), 0.7)
    sweep = 0.85 + 0.15 * np.sin(2 * np.pi * 3 * T / DURATION)
    motor = tone(blade * 2) + 0.5 * tone(blade * 4) + 0.2 * tone(blade * 6)
    return air * chop * sweep + 0.07 * np.stack([motor, motor])


def electric_hum(rng):
    out = np.zeros(N)
    for harmonic in range(1, 24):
        level = (1.0 if harmonic % 2 == 0 else 0.55) / harmonic**1.15
        out += level * tone(50 * harmonic, rng.uniform(0, 6))
    buzz = 0.75 + 0.25 * np.abs(tone(50))
    out *= buzz
    fizz = stereo_noise(rng, band(2500, 9000, -2), 0.9) * (np.abs(tone(50)) ** 6)[None, :]
    wobble = 0.9 + 0.1 * lfo(rng, 6)
    return np.stack([out, np.roll(out, 23)]) * wobble + 0.035 * fizz


def spaceship(rng):
    out = np.zeros((2, N))
    for freq, level in [(41.0, 1.0), (41.4, 0.8), (55.2, 0.6), (82.5, 0.35), (82.9, 0.3), (123.7, 0.14)]:
        out += level * pan(tone(freq, rng.uniform(0, 6)), rng.uniform(-0.5, 0.5))
    out *= 0.35
    rumble = stereo_noise(rng, band(20, 140, -6), 0.5) * (0.6 + 0.4 * lfo(rng, 6))
    out += 0.5 * rumble
    for center in (260, 410, 640, 980, 1500):
        pad = stereo_noise(rng, peak(center, 0.07) * band(100, 4000), 0.9)
        out += 0.20 * pad * lfo(rng, 5) ** 2
    air = stereo_noise(rng, band(1500, 8000, -5), 1.0)
    return out + 0.035 * air


def console_beeps(rng):
    hum = stereo_noise(rng, band(80, 2200, -5), 0.7)
    out = 0.20 * hum + 0.03 * np.stack([tone(60), tone(60)])
    scale = [880, 987.77, 1108.73, 1318.51, 1479.98, 1760, 1975.53, 2217.46]
    position = 0
    while position < N - SR:
        position += int(rng.uniform(0.25, 1.9) * SR)
        burst = rng.integers(1, 5)
        side = rng.uniform(-0.7, 0.7)
        for _ in range(burst):
            length = int(rng.choice([0.05, 0.08, 0.12]) * SR)
            t = np.arange(length) / SR
            freq = scale[rng.integers(len(scale))]
            envelope = np.minimum(1, np.arange(length) / (0.004 * SR)) * np.minimum(1, (length - np.arange(length)) / (0.012 * SR))
            beep = (np.sin(2 * np.pi * freq * t) + 0.15 * np.sin(4 * np.pi * freq * t)) * envelope
            add_at(out, pan(0.22 * beep, side), position)
            position += length + int(0.035 * SR)
    for _ in range(14):
        length = int(rng.uniform(0.15, 0.5) * SR)
        ticks = (rng.uniform(size=length) < 0.004) * rng.uniform(0.3, 1.0, size=length)
        add_at(out, pan(0.5 * ticks, rng.uniform(-0.8, 0.8)), rng.integers(N))
    return circular_filter(out, band(40, 9000))


# ------------------------------------------------------------------------------ météo


def drops(rng, out, count, lo, hi, level):
    for _ in range(count):
        length = int(rng.uniform(0.0015, 0.008) * SR)
        t = np.arange(length) / SR
        f0 = rng.uniform(lo, hi)
        tick = (np.sin(2 * np.pi * f0 * t) + 0.6 * rng.standard_normal(length)) * decay(length, rng.uniform(0.0006, 0.002))
        add_at(out, pan(level * rng.uniform(0.1, 1.0) ** 2 * tick, rng.uniform(-1, 1)), rng.integers(N))


def light_rain(rng):
    out = 0.5 * stereo_noise(rng, band(1800, 12000, -1.5), 0.95) * (0.8 + 0.2 * lfo(rng, 10, 2))
    out += 0.12 * stereo_noise(rng, band(200, 1500, -5), 0.8)
    drops(rng, out, 5200, 1800, 6500, 1.3)
    return out


def heavy_rain(rng):
    sheet = stereo_noise(rng, band(350, 13000, -1.2) * (1 + 0.7 * peak(3000, 1.0)), 0.95)
    out = 0.85 * sheet * (0.82 + 0.18 * lfo(rng, 9, 2))
    out += 0.35 * stereo_noise(rng, band(50, 400, -5), 0.6) * (0.7 + 0.3 * lfo(rng, 14, 4))
    drops(rng, out, 9000, 900, 5000, 1.5)
    return out


def gusts(rng, bands, whistle=()):
    main = lfo(rng, 7, 1, 0.7)
    out = np.zeros((2, N))
    for index, (lo, hi, level) in enumerate(bands):
        local = np.clip(0.65 * main + 0.35 * lfo(rng, 9, 2), 0, 1) ** (1.2 + 0.7 * index)
        out += level * stereo_noise(rng, band(lo, hi, -4), 0.85) * (0.12 + local)
    for center, level in whistle:
        tone_band = stereo_noise(rng, peak(center, 0.035), 0.6)
        out += level * tone_band * np.clip(main * 1.3 - 0.35, 0, 1) ** 2 * lfo(rng, 13, 3)
    return out


def wind(rng):
    return gusts(rng, [(60, 260, 1.0), (240, 620, 0.75), (600, 1400, 0.42), (1300, 3200, 0.20)], [(520, 0.25)])


def blizzard(rng):
    out = gusts(
        rng,
        [(80, 300, 0.8), (280, 800, 0.9), (750, 2000, 0.7), (1900, 5200, 0.42), (5000, 11000, 0.16)],
        [(760, 0.55), (1180, 0.4), (1650, 0.22)],
    )
    grit = stereo_noise(rng, band(4000, 12000, -1), 1.0) * lfo(rng, 40, 10) ** 3
    return out + 0.15 * grit


def distant_storm(rng):
    out = 0.32 * stereo_noise(rng, band(1500, 11000, -2), 0.95) * (0.8 + 0.2 * lfo(rng, 8, 2))
    out += 0.12 * stereo_noise(rng, band(150, 1200, -5), 0.8)
    drops(rng, out, 2600, 1800, 6000, 0.9)
    for start_s in (2.0, 12.5, 22.0):
        length = int(rng.uniform(6.5, 9.0) * SR)
        t = np.arange(length) / SR
        grain_rng = np.random.default_rng(rng.integers(1 << 31))
        raw = grain_rng.standard_normal(length)
        f = np.fft.rfftfreq(length, 1 / SR)
        shape = 1 / (1 + (f / 85.0) ** 3) * (f > 18)
        raw = np.fft.irfft(np.fft.rfft(raw) * shape, length)
        raw /= np.sqrt(np.mean(raw**2)) + 1e-12
        envelope = np.minimum(1, t / 0.25) * np.exp(-t / rng.uniform(1.8, 2.8))
        rolls = 0.55 + 0.45 * np.abs(np.sin(2 * np.pi * rng.uniform(0.35, 0.8) * t + rng.uniform(0, 6)))
        envelope *= rolls * np.minimum(1, (t[-1] - t) / 0.8)
        rumble = raw * envelope * rng.uniform(1.6, 2.4)
        add_at(out, pan(rumble, rng.uniform(-0.4, 0.4)), int((start_s + rng.uniform(-0.8, 0.8)) * SR))
    return out


# ------------------------------------------------------------------------- transports


def train(rng):
    rumble = stereo_noise(rng, band(30, 260, -5), 0.5) * (0.85 + 0.15 * lfo(rng, 8))
    hiss = stereo_noise(rng, band(1200, 7000, -4), 0.9) * (0.7 + 0.3 * lfo(rng, 20, 5))
    out = 0.8 * rumble + 0.10 * hiss
    period = DURATION / 34
    for beat in range(34):
        for offset, level in ((0.0, 1.0), (0.115, 0.8), (period * 0.5, 0.55), (period * 0.5 + 0.115, 0.45)):
            length = int(0.11 * SR)
            t = np.arange(length) / SR
            thud = np.sin(2 * np.pi * 78 * t * (1 - 0.3 * t / t[-1])) * decay(length, 0.022)
            click = rng.standard_normal(length) * decay(length, 0.004)
            clack = 1.5 * thud + 0.35 * click
            add_at(out, pan(level * rng.uniform(0.85, 1.1) * clack, rng.uniform(-0.25, 0.25)), int((beat * period + offset) * SR))
    sway = 0.9 + 0.1 * np.sin(2 * np.pi * 5 * T / DURATION)
    return circular_filter(out * sway, band(25, 9000))


def plane_cabin(rng):
    air = stereo_noise(rng, band(70, 5500, -4) * (1 + 0.9 * peak(230, 0.9)), 0.85)
    out = air * (0.94 + 0.06 * lfo(rng, 6))
    for freq, level in [(108.0, 0.11), (108.5, 0.09), (216.4, 0.05), (325.0, 0.02)]:
        out += level * 3 * pan(tone(freq, rng.uniform(0, 6)), rng.uniform(-0.4, 0.4))
    out += 0.05 * stereo_noise(rng, band(3500, 9000, -2), 1.0)
    return out


def car_interior(rng):
    road = stereo_noise(rng, band(28, 190, -4), 0.4) * (0.8 + 0.2 * lfo(rng, 12, 2))
    tyres = stereo_noise(rng, band(350, 2200, -6), 0.85) * (0.7 + 0.3 * lfo(rng, 10, 2))
    out = 0.85 * road + 0.16 * tyres
    engine = np.zeros(N)
    drift = 1 + 0.012 * (lfo(rng, 4) - 0.5)
    base = 74.0 * drift
    cycles = np.sum(base) / SR
    base *= round(cycles) / cycles
    phase = 2 * np.pi * np.cumsum(base) / SR
    for harmonic, level in ((1, 1.0), (2, 0.55), (3, 0.22), (4, 0.12), (6, 0.05)):
        engine += level * np.sin(harmonic * phase + rng.uniform(0, 6))
    out += 0.10 * np.stack([engine, np.roll(engine, 31)])
    for _ in range(9):
        length = int(0.09 * SR)
        t = np.arange(length) / SR
        bump = np.sin(2 * np.pi * 52 * t) * decay(length, 0.025)
        add_at(out, pan(0.9 * bump, rng.uniform(-0.3, 0.3)), rng.integers(N))
    return out


def boat(rng):
    beats = round(6.6 * DURATION)
    period = N / beats
    out = 0.25 * stereo_noise(rng, band(35, 240, -5), 0.4)
    for beat in range(beats):
        length = int(0.13 * SR)
        t = np.arange(length) / SR
        chug = np.sin(2 * np.pi * 58 * t) * decay(length, 0.03) + 0.3 * np.sin(2 * np.pi * 117 * t) * decay(length, 0.02)
        chug += 0.12 * rng.standard_normal(length) * decay(length, 0.006)
        accent = 1.0 if beat % 2 == 0 else 0.8
        add_at(out, pan(0.9 * accent * chug, 0.0), int(beat * period))
    cycles = 9
    phase = (T / DURATION * cycles + 0.2 * np.sin(2 * np.pi * 2 * T / DURATION)) % 1.0
    lap = circular_smooth(np.where(phase < 0.3, np.sin(np.pi * phase / 0.6) ** 2, np.exp(-(phase - 0.3) * 4.0)), 3.0)
    water = stereo_noise(rng, band(250, 6000, -3), 0.95)
    out += 0.42 * water * (0.2 + np.clip(lap, 0, None))
    return circular_filter(out, band(28, 10000))


# ---------------------------------------------------------------------------- catalogue

PAGES = [
    ("nature", "Nature", "Nature", "forest"),
    ("ville", "Ville", "City", "location_city"),
    ("technologique", "Technologique", "Technology", "memory"),
    ("meteo", "Météo", "Weather", "thunderstorm"),
    ("transports", "Transports", "Transport", "train"),
]

# (identifiant, page, nom français, nom anglais, symbole, volume par défaut, générateur)
SOUNDS = [
    ("birds", "nature", "Oiseaux", "Birds", "flutter_dash", 60, birds),
    ("stream", "nature", "Ruisseau", "Stream", "water", 65, stream),
    ("waves", "nature", "Vagues", "Waves", "waves", 70, waves),
    ("campfire", "nature", "Feu de camp", "Campfire", "local_fire_department", 70, campfire),
    ("crickets", "nature", "Grillons", "Crickets", "bug_report", 50, crickets),
    ("traffic", "ville", "Circulation", "Traffic", "traffic", 60, traffic),
    ("crowd", "ville", "Foule", "Crowd", "groups", 60, crowd),
    ("fountain", "ville", "Fontaine", "Fountain", "water_drop", 60, fountain),
    ("pigeons", "ville", "Pigeons", "Pigeons", "emoji_nature", 55, pigeons),
    ("city_night", "ville", "Nuit en ville", "City night", "nights_stay", 65, city_night),
    ("server_room", "technologique", "Salle des serveurs", "Server room", "dns", 60, server_room),
    ("fan", "technologique", "Ventilateur", "Fan", "wind_power", 60, fan),
    ("electric_hum", "technologique", "Bourdonnement électrique", "Electric hum", "bolt", 50, electric_hum),
    ("spaceship", "technologique", "Vaisseau spatial", "Spaceship", "rocket_launch", 70, spaceship),
    ("console_beeps", "technologique", "Bips de console", "Console beeps", "terminal", 50, console_beeps),
    ("light_rain", "meteo", "Pluie légère", "Light rain", "water_drop", 65, light_rain),
    ("heavy_rain", "meteo", "Pluie forte", "Heavy rain", "umbrella", 65, heavy_rain),
    ("wind", "meteo", "Vent", "Wind", "air", 65, wind),
    ("distant_storm", "meteo", "Orage lointain", "Distant storm", "thunderstorm", 70, distant_storm),
    ("blizzard", "meteo", "Blizzard", "Blizzard", "ac_unit", 60, blizzard),
    ("train", "transports", "Train", "Train", "train", 65, train),
    ("plane_cabin", "transports", "Cabine d'avion", "Plane cabin", "flight", 60, plane_cabin),
    ("car_interior", "transports", "Voiture", "Car", "directions_car", 65, car_interior),
    ("boat", "transports", "Bateau", "Boat", "directions_boat", 65, boat),
]

SOURCE = "https://github.com/MrLouix/Sonorama/blob/main/tools/generate_base_pack.py"

LICENSES_MD = """# Licences des sons — pack de base Sonorama

Tous les sons de ce pack sont des créations originales, synthétisées par le script
`tools/generate_base_pack.py` du dépôt Sonorama. Ils sont placés dans le domaine public
sous licence [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/deed.fr).

| Fichier | Nom | Auteur | Licence |
| --- | --- | --- | --- |
"""


def write_ogg(stereo: np.ndarray, path: Path, quality: float) -> None:
    pcm = (np.clip(stereo.T, -1, 1) * 32767).astype("<i2")
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
        wav_path = Path(handle.name)
    try:
        with wave.open(str(wav_path), "wb") as wav:
            wav.setnchannels(2)
            wav.setsampwidth(2)
            wav.setframerate(SR)
            wav.writeframes(pcm.tobytes())
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", str(quality),
             "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-serial_offset", "1", str(path)],
            check=True,
        )
    finally:
        wav_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--only", action="append", help="ne régénérer que ce son (répétable)")
    parser.add_argument("--quality", type=float, default=3.0, help="qualité Vorbis (3 ≈ 112 kbit/s)")
    parser.add_argument("--version", type=int, default=1, help="version du pack")
    args = parser.parse_args()

    audio_dir = args.out / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    licenses = LICENSES_MD
    manifest_sounds = []
    for sound_id, page, name_fr, name_en, symbol, volume, generator in SOUNDS:
        file_name = f"audio/{sound_id}.ogg"
        if not args.only or sound_id in args.only:
            stereo = finish(generator(rng_for(sound_id)))
            edge = float(np.abs(stereo[:, 0] - stereo[:, -1]).max())
            write_ogg(stereo, args.out / file_name, args.quality)
            size = (args.out / file_name).stat().st_size // 1024
            print(f"{sound_id:14s} crête {np.abs(stereo).max():.2f}  raccord {edge:.4f}  {size} Ko")
        manifest_sounds.append(
            {
                "id": sound_id,
                "file": file_name,
                "name": {"fr": name_fr, "en": name_en},
                "symbol": symbol,
                "defaultVolume": volume,
                "page": page,
                "author": "Sonorama",
                "license": "CC0-1.0",
                "source": SOURCE,
            }
        )
        licenses += f"| `{file_name}` | {name_fr} | Sonorama | CC0 1.0 |\n"

    manifest = {
        "format": 1,
        "id": "sonorama.base",
        "version": args.version,
        "name": {"fr": "Pack de base", "en": "Base pack"},
        "pages": [{"id": i, "name": {"fr": fr, "en": en}, "symbol": s} for i, fr, en, s in PAGES],
        "sounds": manifest_sounds,
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out / "LICENSES.md").write_text(licenses, encoding="utf-8")


if __name__ == "__main__":
    main()
