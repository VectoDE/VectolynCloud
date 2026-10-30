"""Shared helpers for the Vectolyn brand generators: colour maths, text set as outlined paths, the 8 x 8 mark.

Text is shaped with HarfBuzz (kerning) and emitted as SVG paths, so every SVG looks the same without webfonts.
Fonts are read from branding/fonts (override with the VECTOLYN_FONT_DIR environment variable).
Requires: uharfbuzz, fonttools (see README.md in this folder)."""
import json
import os
import pathlib

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

BRANDING = pathlib.Path(__file__).resolve().parent.parent
TOOLS = pathlib.Path(__file__).resolve().parent
# Brand constants (palette, mark grid, URL, facts): brand.json here, or the file named by VECTOLYN_BRAND.
BRAND = json.loads(pathlib.Path(os.environ.get("VECTOLYN_BRAND") or TOOLS / "brand.json").read_text(encoding="utf-8"))
FONT_DIR = pathlib.Path(os.environ.get("VECTOLYN_FONT_DIR") or BRANDING / "fonts")

# ---------------------------------------------------------------- Colour


def _lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lum(h):
    r, g, b = (_lin(v) for v in rgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


# ---------------------------------------------------------------- Fonts

FONTS = {
    "pixelify": "pixelify-sans/PixelifySans-Variable.ttf",
    "atkinson": "atkinson-hyperlegible-next/AtkinsonHyperlegibleNext-Variable.ttf",
    "jetbrains": "jetbrains-mono/JetBrainsMono-Variable.ttf",
}
_cache = {}


def _font(key, axes):
    k = (key, tuple(sorted(axes.items())))
    if k not in _cache:
        path = str(FONT_DIR / FONTS[key])
        tt = TTFont(path)
        full = {a.axisTag: a.defaultValue for a in tt["fvar"].axes}
        full.update(axes)
        static = instantiateVariableFont(TTFont(path), full)
        face = hb.Face(hb.Blob.from_file_path(path))
        hfont = hb.Font(face)
        hfont.set_variations(full)
        _cache[k] = (static, hfont)
    return _cache[k]


def text(key, s, size, x, y, axes=None, track=0.0, anchor="start", features=None):
    """Set s as an outlined path. track is in em. Returns (path, width)."""
    static, hfont = _font(key, axes or {})
    upm = static["head"].unitsPerEm
    buf = hb.Buffer()
    buf.add_str(s)
    buf.guess_segment_properties()
    hb.shape(hfont, buf, features or {"kern": True, "liga": False})
    sc = size / upm
    infos, pos = buf.glyph_infos, buf.glyph_positions
    width = sum(p.x_advance for p in pos) * sc + track * size * (len(pos) - 1)
    if anchor == "middle":
        x -= width / 2
    elif anchor == "end":
        x -= width
    gs = static.getGlyphSet()
    cx = x
    out = []
    for info, p in zip(infos, pos):
        name = static.getGlyphName(info.codepoint)
        pen = SVGPathPen(gs, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
        gs[name].draw(TransformPen(pen, (sc, 0, 0, -sc, cx + p.x_offset * sc, y - p.y_offset * sc)))
        c = pen.getCommands()
        if c:
            out.append(c)
        cx += p.x_advance * sc + track * size
    return " ".join(out), width


def p(d, fill, extra=""):
    return f'<path d="{d}" fill="{fill}"{extra}/>' if d else ""


# ---------------------------------------------------------------- Mark grid

MARK_A = BRAND["mark"]


def runs(cells, cell):
    """cells: set of (x, y) pixels -> one path built from merged row runs."""
    rows = {}
    for x, y in cells:
        rows.setdefault(y, []).append(x)
    d = []
    for y in sorted(rows):
        xs = sorted(rows[y])
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and abs(x - (prev + cell)) < 1e-6:
                prev = x
                continue
            d.append(f"M{start:g} {y:g}h{prev + cell - start:g}v{cell:g}h{-(prev + cell - start):g}z")
            if x is not None:
                start = prev = x
    return "".join(d)


def mark_a(x, y, cell, arm, tip):
    a, t = [], []
    for r, row in enumerate(MARK_A):
        for c, v in enumerate(row):
            if v == "X":
                a.append((x + c * cell, y + r * cell))
            elif v == "G":
                t.append((x + c * cell, y + r * cell))
    ce = ' shape-rendering="crispEdges"'
    return p(runs(a, cell), arm, ce) + p(runs(t, cell), tip, ce)
