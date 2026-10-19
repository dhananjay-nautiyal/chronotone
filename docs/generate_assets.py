#!/usr/bin/env python3
"""Generates the images README.md uses, from Chronotone's own output.

Nothing here is drawn by hand: every curve is a value the engine actually produced. That is the
point — the docs make the same claim the engine does, so they should be reproducible too.

Inputs
------
--day  CSV from docs/tools/DayDump.java (package dev.chronotone.render): per-slot musical params plus each slot's exact sample
       range, read out of the renderer's own geometry rather than re-derived here.
--wav  a full-day render of the same day, e.g.

         java -jar chronotone-cli/target/chronotone-cli-*.jar render \
           --log .chronotone/station-day.jsonl --day 2026-10-19 \
           --from 00:00 --minutes 1440 --seed 42 --out out/day

Outputs (written into --out, normally docs/)
--------------------------------------------
hero.png      banner: wordmark, tagline, and a real waveform excerpt
terminal.png  a captured station session, rendered as a terminal window
day.png       the day as five bands of real data, audio included
station.gif   the 30-minute station window walking across that day

Usage
-----
  python3 docs/generate_assets.py --wav out/day/chronotone.wav --day out/day/day.csv --out docs
"""

from __future__ import annotations

import argparse
import csv
import os
import re

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ------------------------------------------------------------------------------------- palette

BG_TOP = (13, 17, 24)
BG_BOT = (9, 11, 16)
PANEL = (15, 19, 27)
BORDER = (34, 40, 50)
GRID = (26, 32, 41)
INK = (230, 237, 243)
MUTED = (139, 148, 158)
DIM = (88, 97, 108)

AMBER = (255, 180, 84)
RED = (255, 107, 107)
TEAL = (78, 205, 196)
VIOLET = (179, 136, 255)
GREY = (150, 160, 172)
GREEN = (63, 185, 80)

SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SANS_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
MONO_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

SCALE = 2  # supersample, then downscale with LANCZOS: crisp lines and text on retina displays

_fonts: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    key = (path, size)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(path, size * SCALE)
    return _fonts[key]


def gradient(width: int, height: int, top=BG_TOP, bottom=BG_BOT) -> Image.Image:
    """A vertical gradient, built with numpy so it is smooth rather than banded."""
    ramp = np.linspace(0.0, 1.0, height)[:, None]
    channels = [np.outer(1 - ramp, np.full(width, top[c])) + np.outer(ramp, np.full(width, bottom[c]))
                for c in range(3)]
    return Image.fromarray(np.stack(channels, axis=-1).astype(np.uint8), "RGB")


def text(draw: ImageDraw.ImageDraw, xy, body: str, *, fill=INK, f=None, anchor="la", spacing=0):
    """Draws text, optionally letter-spaced (PIL has no tracking of its own)."""
    if spacing == 0:
        draw.text(xy, body, font=f, fill=fill, anchor=anchor)
        return
    x, y = xy
    for char in body:
        draw.text((x, y), char, font=f, fill=fill, anchor=anchor)
        x += draw.textlength(char, font=f) + spacing * SCALE


# ------------------------------------------------------------------------------------- data


class Day:
    """The day's musical params, and each slot's sample range inside the render."""

    def __init__(self, path: str, wav: str):
        with open(path, newline="") as handle:
            self.rows = [{k: float(v) for k, v in row.items()} for row in csv.DictReader(handle)]

        self.samples = np.fromfile(wav, dtype="<i2", offset=44)  # 16-bit mono, past the WAV header

        # Per-slot peak, straight from the bounds the engine reported. Slices are widened to int32
        # one at a time, so a 400 MB render never needs a second full copy in memory.
        self.peak = np.array([
            int(self._loudest(int(row["start"]), int(row["end"]))) for row in self.rows
        ], dtype=np.int64)

        # A finer envelope for the waveform strips: ~0.22 s per bucket.
        self.fine = np.array([self._loudest(lo, min(len(self.samples), lo + 9850))
                              for lo in range(0, len(self.samples) - 9850, 9850)], dtype=np.float64)
        self.fine = self.fine / max(1.0, self.fine.max())

        self.n = len(self.rows)

    def _loudest(self, start: int, end: int) -> int:
        if end <= start:
            return 0
        return int(np.abs(self.samples[start:end].astype(np.int32)).max())

    def column(self, name: str) -> list[float]:
        return [row[name] for row in self.rows]

    def excerpt(self, seconds: float, buckets: int = 620) -> np.ndarray:
        """A min/max envelope of the busiest stretch of the day, for the banner."""
        busiest = int(np.argmax([row["energy"] for row in self.rows]))
        start = int(self.rows[max(0, busiest - 2)]["start"])
        chunk = np.abs(self.samples[start:start + int(seconds * 44100)].astype(np.int32))
        usable = len(chunk) - (len(chunk) % buckets)
        return chunk[:usable].reshape(buckets, -1).max(axis=1) / max(1, chunk.max())

    def window_strip(self, first_slot: int, slots: int, buckets: int = 460) -> np.ndarray:
        """The waveform of one station window, as min/max buckets."""
        per_slot = len(self.fine) / self.n
        lo = int(first_slot * per_slot)
        hi = int(min(len(self.fine), (first_slot + slots) * per_slot))
        segment = self.fine[lo:hi]
        if len(segment) < buckets:
            return np.pad(segment, (0, buckets - len(segment)))
        usable = len(segment) - (len(segment) % buckets)
        return segment[:usable].reshape(buckets, -1).max(axis=1)


# ------------------------------------------------------------------------------------- pieces


def draw_band(draw, box, values, color, *, vmin=None, vmax=None, baseline=True, alpha=70):
    """Fills one band: a soft body under a bright top edge, like a level meter."""
    x0, y0, x1, y1 = box
    values = np.asarray(values, dtype=float)
    vmin = values.min() if vmin is None else vmin
    vmax = values.max() if vmax is None else vmax
    span = (vmax - vmin) or 1.0

    n = len(values)
    xs = [x0 + i * (x1 - x0) / (n - 1) for i in range(n)]
    ys = [y1 - (v - vmin) / span * (y1 - y0) for v in values]

    if baseline:
        draw.line([(x0, y1), (x1, y1)], fill=BORDER, width=SCALE)

    draw.polygon(list(zip(xs, ys)) + [(x1, y1), (x0, y1)], fill=tuple(
        int(c * alpha / 255 + bg_c * (1 - alpha / 255)) for c, bg_c in zip(color, PANEL)))
    draw.line(list(zip(xs, ys)), fill=color, width=max(1, SCALE))


def day_chart(day: Day, size=(1200, 470), *, caption: str | None = None) -> Image.Image:
    """The day as five stacked bands."""
    width, height = size
    img = gradient(width * SCALE, height * SCALE)
    draw = ImageDraw.Draw(img)
    w, h = img.size

    pad = 26 * SCALE
    gutter = 118 * SCALE
    top = 92 * SCALE
    bottom = 46 * SCALE
    band_gap = 9 * SCALE

    if caption:
        text(draw, (pad, 30 * SCALE), caption, fill=DIM, f=font(MONO, 13), spacing=0.4)

    # name, unit, values, colour, how to print a range, divisor for the printed range
    bands = [
        ("TEMPO", "bpm", day.column("tempo"), AMBER, "{:g}", 1),
        ("ENERGY", "0-1", day.column("energy"), RED, "{:.2f}", 1),
        ("BRIGHTNESS", "0-1", day.column("brightness"), TEAL, "{:.2f}", 1),
        ("HATS", "0-1", day.column("hats"), VIOLET, "{:.2f}", 1),
        # 32768 is full scale for 16-bit; the top of the day should land on the synth's PEAK of 0.89.
        ("AUDIO", "peak 0-1", day.peak, GREY, "{:.2f}", 32768),
    ]
    usable = h - top - bottom - band_gap * (len(bands) - 1)
    band_h = usable // len(bands)

    plot_x0 = pad + gutter
    plot_x1 = w - pad

    for index, (name, unit, values, color, fmt, divisor) in enumerate(bands):
        y0 = top + index * (band_h + band_gap)
        y1 = y0 + band_h
        draw_band(draw, (plot_x0, y0, plot_x1, y1), values, color)

        text(draw, (pad, y0 + 6 * SCALE), name, fill=color, f=font(MONO_BOLD, 15), spacing=0.6)
        span = f"{fmt.format(min(values) / divisor)}…{fmt.format(max(values) / divisor)}"
        text(draw, (pad, y0 + 28 * SCALE), f"{unit}  {span}", fill=DIM, f=font(MONO, 12))

        for hour in range(0, 25, 3):
            x = plot_x0 + (plot_x1 - plot_x0) * hour / 24
            draw.line([(x, y0), (x, y1)], fill=GRID, width=SCALE)
            if index == len(bands) - 1:
                draw.line([(x, y1), (x, y1 + 6 * SCALE)], fill=BORDER, width=SCALE)
                text(draw, (x, y1 + 12 * SCALE), f"{hour:02d}", fill=DIM, f=font(MONO, 12), anchor="ra")

    return img


def hero(day: Day, size=(1200, 300)) -> Image.Image:
    width, height = size
    img = gradient(width * SCALE, height * SCALE)
    draw = ImageDraw.Draw(img)
    w, h = img.size

    pad = 64 * SCALE
    text(draw, (pad, 46 * SCALE), "CHRONOTONE", fill=INK, f=font(SANS_BOLD, 60), spacing=3.2)
    text(draw, (pad + 3 * SCALE, 136 * SCALE), "your life, rendered as sound.",
         fill=AMBER, f=font(SANS, 24))
    text(draw, (pad + 3 * SCALE, 176 * SCALE),
         "deterministic  ·  local-first  ·  MIDI + WAV  ·  zero-dependency core",
         fill=MUTED, f=font(MONO, 14))

    # A real waveform, straight out of the render: a few seconds, so the transients survive.
    mid = 242 * SCALE
    amplitude = 36 * SCALE
    x0, x1 = pad, w - pad
    draw.line([(x0, mid), (x1, mid)], fill=GRID, width=SCALE)
    for second in range(1, 9):
        x = x0 + (x1 - x0) * second / 8
        draw.line([(x, mid - amplitude - 4 * SCALE), (x, mid + amplitude + 4 * SCALE)],
                  fill=(22, 28, 38), width=SCALE)

    env = day.excerpt(seconds=8.0, buckets=560)
    xs = [x0 + i * (x1 - x0) / (len(env) - 1) for i in range(len(env))]
    upper = [(xs[i], mid - env[i] * amplitude) for i in range(len(env))]
    lower = [(xs[i], mid + env[i] * amplitude) for i in range(len(env))]
    draw.polygon(upper + lower[::-1], fill=(46, 36, 23))
    draw.line(upper, fill=AMBER, width=max(1, SCALE))
    draw.line(lower, fill=(120, 84, 38), width=max(1, SCALE))

    return img


STAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z")
DIGEST = re.compile(r"[0-9a-f]{12,64}")
SPLIT = re.compile(f"({STAMP.pattern}|{DIGEST.pattern})")


def _spans(line: str) -> list[tuple[str, tuple[int, int, int]]]:
    """Turns one captured line into coloured spans: labels dim, stamps teal, digests amber."""
    spans: list[tuple[str, tuple[int, int, int]]] = []
    rest = line
    if line.startswith("$ "):
        spans.append(("$ ", GREEN))
        rest = line[2:]
    elif line.startswith("OK"):
        spans.append(("OK", GREEN))
        rest = line[2:]
    elif (label := re.match(r"^(\S+\s{2,})", line)):
        spans.append((label.group(1), DIM))
        rest = line[label.end():]

    for word in re.findall(r"\s*\S+\s*", rest):
        for part in SPLIT.split(word):
            if not part:
                continue
            if STAMP.fullmatch(part):
                spans.append((part, TEAL))
            elif DIGEST.fullmatch(part):
                spans.append((part, AMBER))
            else:
                spans.append((part, INK))
    return spans


def _wrap(spans, draw, f, width: float):
    """Greedy word wrap; returns a list of lines, each a list of coloured spans."""
    lines: list[list[tuple[str, tuple[int, int, int]]]] = []
    current: list[tuple[str, tuple[int, int, int]]] = []
    used = 0.0
    for body, color in spans:
        size = draw.textlength(body, font=f)
        if current and used + size > width:
            lines.append(current)
            current, used = [], 0.0
        current.append((body, color))
        used += size
    if current:
        lines.append(current)
    return lines


def terminal(session: str, size=(1040, 402)) -> Image.Image:
    """Draws docs/tools/station-session.txt — a real capture, wrapped like a terminal would."""
    width, height = size
    img = gradient(width * SCALE, height * SCALE)
    draw = ImageDraw.Draw(img)
    w, h = img.size

    margin = 18 * SCALE
    draw.rounded_rectangle([margin, margin, w - margin, h - margin], radius=12 * SCALE,
                           fill=PANEL, outline=BORDER, width=SCALE)

    bar = margin + 40 * SCALE
    for i, color in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        cx = margin + (26 + i * 22) * SCALE
        draw.ellipse([cx - 6 * SCALE, margin + 14 * SCALE, cx + 6 * SCALE, margin + 26 * SCALE],
                     fill=color)
    text(draw, ((margin + w - margin) / 2, margin + 13 * SCALE), "chronotone — live station",
         fill=DIM, f=font(MONO, 13), anchor="ma")
    draw.line([(margin, bar), (w - margin, bar)], fill=BORDER, width=SCALE)

    body = font(MONO, 13)
    content_x = margin + 26 * SCALE
    content_w = w - margin - 26 * SCALE - content_x
    hang = draw.textlength(" " * 11, font=body)

    y = bar + 22 * SCALE
    line_h = 24 * SCALE
    for raw in open(session, encoding="utf-8").read().rstrip("\n").split("\n"):
        if not raw.strip():
            y += line_h * 0.55
            continue
        for index, wrapped in enumerate(_wrap(_spans(raw), draw, body, content_w)):
            x = content_x + (hang if index else 0)
            for part, color in wrapped:
                draw.text((x, y), part, font=body, fill=color)
                x += draw.textlength(part, font=body)
            y += line_h

    return img


# ------------------------------------------------------------------------------------- animation


def station_gif(day: Day, path: str, size=(820, 268), frames=48, slots=30, duration=120):
    """Animates the phase-4 idea: a 30-minute window walking across the day.

    Deliberately its own compact layout rather than a reuse of the five-band chart — the moving window
    needs room to breathe, and a smaller canvas keeps the GIF worth loading.
    """
    width, height = size
    pad = 22 * SCALE
    lane_top = 92 * SCALE
    lane_h = 74 * SCALE
    strip_y = height * SCALE - 42 * SCALE
    strip_h = 22 * SCALE
    plot_x0 = pad
    plot_x1 = width * SCALE - pad

    peak = np.asarray(day.peak, dtype=float)
    peak = peak / max(1.0, peak.max())
    xs = [plot_x0 + i * (plot_x1 - plot_x0) / (day.n - 1) for i in range(day.n)]

    base = gradient(width * SCALE, height * SCALE)
    draw = ImageDraw.Draw(base)
    draw.polygon(list(zip(xs, [lane_top + lane_h - v * lane_h for v in peak]))
                 + [(plot_x1, lane_top + lane_h), (plot_x0, lane_top + lane_h)],
                 fill=(28, 33, 42))
    draw.line(list(zip(xs, [lane_top + lane_h - v * lane_h for v in peak])), fill=(74, 82, 94),
              width=max(1, SCALE))
    for hour in range(0, 25, 3):
        x = plot_x0 + (plot_x1 - plot_x0) * hour / 24
        draw.line([(x, lane_top + lane_h), (x, lane_top + lane_h + 5 * SCALE)], fill=BORDER,
                  width=SCALE)
        text(draw, (x, lane_top + lane_h + 9 * SCALE), f"{hour:02d}", fill=DIM, f=font(MONO, 11),
             anchor="ra")
    text(draw, (pad, 26 * SCALE), "STATION · 30-MINUTE WINDOW WALKING A DAY", fill=DIM,
         f=font(MONO, 12), spacing=0.5)

    steps = max(1, (day.n - slots) // (frames - 1))
    built = []
    for index in range(frames):
        first = min(day.n - slots, index * steps)
        frame = base.copy()
        draw = ImageDraw.Draw(frame)

        x0 = plot_x0 + (plot_x1 - plot_x0) * first / (day.n - 1)
        x1 = plot_x0 + (plot_x1 - plot_x0) * (first + slots) / (day.n - 1)
        window = peak[first:first + slots]
        draw.polygon(
            [(x0 + i * (x1 - x0) / (len(window) - 1), lane_top + lane_h - v * lane_h)
             for i, v in enumerate(window)] + [(x1, lane_top + lane_h), (x0, lane_top + lane_h)],
            fill=(96, 66, 26))
        draw.line([(x0 + i * (x1 - x0) / (len(window) - 1), lane_top + lane_h - v * lane_h)
                   for i, v in enumerate(window)], fill=AMBER, width=max(1, SCALE))
        draw.rectangle([x0, lane_top - 6 * SCALE, x1, lane_top + lane_h], outline=INK, width=SCALE)

        strip = day.window_strip(first, slots, buckets=430)
        sx = [plot_x0 + i * (plot_x1 - plot_x0) / (len(strip) - 1) for i in range(len(strip))]
        upper = [(sx[i], strip_y - strip[i] * strip_h / 2) for i in range(len(strip))]
        lower = [(sx[i], strip_y + strip[i] * strip_h / 2) for i in range(len(strip))]
        draw.polygon(upper + lower[::-1], fill=(46, 36, 23))
        draw.line(upper, fill=AMBER, width=max(1, SCALE))

        row = day.rows[first]
        clock = f"{first // 60:02d}:{first % 60:02d}"
        text(draw, (pad, 46 * SCALE), clock, fill=INK, f=font(MONO_BOLD, 24))
        text(draw, (pad + draw.textlength(clock, font=font(MONO_BOLD, 24)) + 8 * SCALE, 56 * SCALE),
             "UTC", fill=DIM, f=font(MONO, 11))
        text(draw, (plot_x1, 52 * SCALE),
             f"tempo {int(row['tempo'])} bpm   energy {row['energy']:.2f}   "
             f"hats {row['hats']:.2f}   reverb {row['reverb']:.2f}",
             fill=MUTED, f=font(MONO, 12), anchor="ra")
        text(draw, (pad, height * SCALE - 15 * SCALE),
             "now playing — the window above, byte-for-byte", fill=DIM, f=font(MONO, 11))

        built.append(frame.resize((width, height), Image.LANCZOS))

    frames_p = [f.convert("P", palette=Image.ADAPTIVE, colors=64, dither=Image.NONE) for f in built]
    frames_p[0].save(path, save_all=True, append_images=frames_p[1:], duration=duration, loop=0,
                     optimize=True)


# ------------------------------------------------------------------------------------- main


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the README's images.")
    parser.add_argument("--wav", required=True, help="a full-day render")
    parser.add_argument("--day", required=True, help="CSV from docs/tools/DayDump.java")
    parser.add_argument("--session", default="docs/tools/station-session.txt",
                        help="a captured station session to draw")
    parser.add_argument("--out", default="docs", help="where to write the images")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    day = Day(args.day, args.wav)

    outputs = {
        "hero.png": hero(day),
        "terminal.png": terminal(args.session),
        "day.png": day_chart(
            day, caption="SYNTHETIC DAY · SEED 42 · 1440 BARS · PHRYGIAN, ROOT 8 · RENDERED BY CHRONOTONE"),
    }
    for name, image in outputs.items():
        image.resize((image.width // SCALE, image.height // SCALE), Image.LANCZOS) \
            .save(os.path.join(args.out, name), optimize=True)
    station_gif(day, os.path.join(args.out, "station.gif"))

    for name in [*outputs, "station.gif"]:
        target = os.path.join(args.out, name)
        print(f"{name:14s} {os.path.getsize(target) / 1024:8.1f} KB")


if __name__ == "__main__":
    main()
