"""Vectolyn brand set, version 3: mark, wordmark with optical spacing, lockup system, voxel mark,
banners (social preview block, release banner block, README header). Everything as paths, no webfonts in the SVG."""
import core as ga

CE = ' shape-rendering="crispEdges"'

C = ga.BRAND["palette"]
DARK = dict(bg=C["obsidian"], bg2=C["obsidian2"], prim=C["cyan"], acc=C["gold"], text=C["text_d"], muted=C["muted_d"])
LIGHT = dict(bg=C["quartz"], bg2=C["quartz2"], prim=C["cyan_l"], acc=C["gold_l"], text=C["text_l"], muted=C["muted_l"])


def mono(col):
    return dict(bg="none", prim=col, acc=col, text=col, muted=col)


def _num(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def _poly(pts, x, y, s):
    return "M" + "L".join(f"{_num(x + px * s)} {_num(y + py * s)}" for px, py in pts) + "Z"


# ---------------------------------------------------------------- Mark
# 8-unit grid. Inner edge parallel to the outer edge (slope 2:5), base thickness = stroke thickness perpendicular
# to the diagonal (2 * cos(atan(0.4)) = 1.857), gold block 2 x 2 with a gap of 1 unit.
_T = 5 - 2 * 0.9285
_IX = 2 + 0.4 * _T
VEC_V = [(0, 0), (2, 0), (_IX, _T), (8 - _IX, _T), (6, 0), (8, 0), (6, 5), (2, 5)]
VEC_DOT = [(3, 6), (5, 6), (5, 8), (3, 8)]


def mark(c, x=0, y=0, size=64):
    """The one mark: pixel construction on an 8 x 8 grid at any size, crisp edges."""
    return ga.mark_a(x, y, size / 8, c["prim"], c["acc"])


def mark_pixel(c, x=0, y=0, size=64):
    assert size % 8 == 0
    return ga.mark_a(int(x), int(y), size // 8, c["prim"], c["acc"])


# ---------------------------------------------------------------- Vector wordmark
# 7 rows tall. Verticals 2 units, horizontals 1. Corners that would be round in a typeface are chamfered at 45 degrees.
# Polygons or cell grids (X full, a/b/c/d: cell with the chamfered corner at top left/top right/
# bottom left/bottom right).
VG = {
    "V": [[(0, 0), (2, 0), (3.5, 5), (5, 0), (7, 0), (4.5, 7), (2.5, 7)]],
    "E": [[(0, 0), (5, 0), (5, 1), (2, 1), (2, 3), (4, 3), (4, 4), (2, 4), (2, 6), (5, 6), (5, 7), (0, 7)]],
    "C": [[(1, 0), (6, 0), (6, 1), (2, 1), (2, 6), (6, 6), (6, 7), (1, 7), (0, 6), (0, 1)]],
    "T": [[(0, 0), (6, 0), (6, 1), (4, 1), (4, 7), (2, 7), (2, 1), (0, 1)]],
    "O": [[(1, 0), (5, 0), (6, 1), (6, 6), (5, 7), (1, 7), (0, 6), (0, 1)], [(2, 1), (4, 1), (4, 6), (2, 6)]],
    "L": [[(0, 0), (2, 0), (2, 6), (5, 6), (5, 7), (0, 7)]],
    "Y": [[(0, 0), (2, 0), (3, 2), (4, 0), (6, 0), (4, 3.5), (4, 7), (2, 7), (2, 3.5)]],
    "N": [[(0, 0), (2, 0), (4, 4), (4, 0), (6, 0), (6, 7), (4, 7), (2, 3), (2, 7), (0, 7)]],
    "U": [[(0, 0), (2, 0), (2, 6), (4, 6), (4, 0), (6, 0), (6, 6), (5, 7), (1, 7), (0, 6)]],
    "D": [[(0, 0), (5, 0), (6, 1), (6, 6), (5, 7), (0, 7)], [(2, 1), (4, 1), (4, 6), (2, 6)]],
    ".": [[(0, 5), (2, 5), (2, 7), (0, 7)]],
    "v": [[(0, 2), (2, 2), (3, 4.5), (4, 2), (6, 2), (4, 7), (2, 7)]],
    "7": [[(0, 0), (6, 0), (6, 1.5), (4, 7), (2, 7), (4, 1), (0, 1)]],
    "0": ["aXXXXb", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "cXXXXd"],
    "1": ["aXXX..", "..XX..", "..XX..", "..XX..", "..XX..", "..XX..", "XXXXXX"],
    "2": ["aXXXXb", "....XX", "....XX", "aXXXXd", "XX....", "XX....", "XXXXXX"],
    "3": ["XXXXXb", "....XX", "....XX", "..XXXX", "....XX", "....XX", "XXXXXd"],
    "4": ["XX..XX", "XX..XX", "XX..XX", "XXXXXX", "....XX", "....XX", "....XX"],
    "5": ["XXXXXX", "XX....", "XX....", "XXXXXb", "....XX", "....XX", "XXXXXd"],
    "6": ["aXXXXX", "XX....", "XX....", "XXXXXb", "XX..XX", "XX..XX", "cXXXXd"],
    "8": ["aXXXXb", "XX..XX", "XX..XX", "XXXXXX", "XX..XX", "XX..XX", "cXXXXd"],
    "9": ["aXXXXb", "XX..XX", "XX..XX", "cXXXXX", "....XX", "....XX", "XXXXXd"],
}
WIDTH = {"V": 7, "E": 5, "C": 6, "T": 6, "O": 6, "L": 5, "Y": 6, "N": 6, "U": 6, "D": 6, ".": 2, "v": 6, "7": 6}
# Optical compensation in units (relative to the default gap of 1): open shapes move closer together.
KERN = {("L", "Y"): -1.0, ("Y", "N"): -0.5, ("V", "E"): -0.25, ("T", "O"): -0.5, ("L", "O"): -0.5,
        ("C", "T"): -0.25, ("v", "0"): -0.5, ("v", "1"): -0.5, ("v", "2"): -0.5}
CELL = {"X": [(0, 0), (1, 0), (1, 1), (0, 1)], "a": [(1, 0), (1, 1), (0, 1)], "b": [(0, 0), (1, 1), (0, 1)],
        "c": [(0, 0), (1, 0), (1, 1)], "d": [(0, 0), (1, 0), (0, 1)]}


def glyph_polys(ch):
    g = VG[ch]
    if isinstance(g[0], str):
        polys = []
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v in CELL:
                    polys.append([(c + px, r + py) for px, py in CELL[v]])
        return polys, len(g[0])
    return g, WIDTH[ch]


# Wordmark and headlines in Pixelify Sans (OFL), as paths. One font pixel = u; cap height 7 pixels = 7u.
# Weight 700 sits next to the heavy mark. Tracking -0.0286 em: letter gap of exactly one font pixel.
PX_UNIT = 91.43  # font pixel in font units (upm 1000)
PX_CAP = 631     # top of the capitals above the baseline
PX_LSB = 61      # left side bearing, so the ink starts at x
PX_WGHT = 700
PX_TRACK = -0.0286


def vec_text(s, x, y, u, gap=None, space=None, colors=None, wght=PX_WGHT, track=PX_TRACK):
    """Set s in Pixelify Sans. y = top of the capitals, u = font pixel. colors: colour per character.
    -> ({colour|None: path}, ink width)"""
    sc = u / PX_UNIT
    size = 1000 * sc
    base = y + PX_CAP * sc
    x0 = x - PX_LSB * sc
    runs, cur = [], None
    for i, ch in enumerate(s):
        col = colors[i] if colors else None
        if cur and cur[0] == col:
            cur[1] += ch
        else:
            cur = [col, ch, i]
            runs.append(cur)
    out = {}
    for col, txt, i in runs:
        _, pre = ga.text("pixelify", s[:i], size, 0, 0, {"wght": wght}, track=track) if i else ("", 0)
        if i:
            pre += track * size
        d, _ = ga.text("pixelify", txt, size, x0 + pre, base, {"wght": wght}, track=track)
        out[col] = (out.get(col, "") + " " + d).strip()
    _, full = ga.text("pixelify", s, size, 0, 0, {"wght": wght}, track=track)
    return out, full - 2 * PX_LSB * sc


def word(s, x, y, u, fill, **kw):
    d, w = vec_text(s, x, y, u, **kw)
    return ga.p(d[None], fill), w


# ---------------------------------------------------------------- Lockups
# Two types (owner rule):
#  initial: the mark IS the V. [mark]ECTOLYN CLOUD, [mark]ECTOLYN.NET. Cap height = V body (5m),
#           top and baseline aligned, the gold block hangs below as a descender, gap to the E as between V and E in running text.
#  symbol:  mark standing alone, clear space 4m (two gold blocks), then the full name, centred on the mark
#           so that nothing reads as an initial.
# Mark height H, grid unit m = H/8.
LOCKUP_TEXT = {"product": ("VECTOLYN", "CLOUD", "prim", True), "net": ("VECTOLYN", ".NET", "acc", False),
               "dotcloud": ("VECTOLYN", ".CLOUD", "acc", False)}
INITIAL_GAP = 1.0  # one font pixel, as between the letters


def lockup(c, H, kind, pixel=False, style="initial"):
    """-> (svg body, width). With pixel=True the height is 8*cell + PIX_DY (see lockup_h)."""
    m = H / 8
    a, b, key, spaced = LOCKUP_TEXT[kind]
    if style == "initial":
        a = a[1:]
    if pixel:
        cell = int(m)
        t = max(1, round(5 * cell / 7))
        dy = 7 * t - 5 * cell  # baseline of text and V body aligned
        body = ga.mark_a(0, max(dy, 0), cell, c["prim"], c["acc"])
        ty = max(-dy, 0)
        x0 = 8 * cell + t if style == "initial" else 12 * cell
        if style != "initial":
            ty = (8 * cell - 7 * t) // 2 + max(dy, 0)
        d1, w1 = vec_text(a, x0, ty, t)
        d2, w2 = vec_text(b, x0 + w1 + t, ty, t)
        return body + ga.p(d1[None], c["text"]) + ga.p(d2[None], c[key]), x0 + w1 + t + w2
    u = 5 * m / 7
    body = mark(c, 0, 0, H)
    if style == "initial":
        x0, ty = 8 * m + INITIAL_GAP * u, 0
    else:
        x0, ty = 12 * m, (H - 7 * u) / 2
    d1, w1 = vec_text(a, x0, ty, u)
    gap = 1 * u  # VectolynCloud is one word; the colour separates it
    d2, w2 = vec_text(b, x0 + w1 + gap, ty, u)
    return body + ga.p(d1[None], c["text"]) + ga.p(d2[None], c[key]), x0 + w1 + gap + w2


def lockup_h(H, pixel=False):
    if not pixel:
        return H
    cell = int(H / 8)
    t = max(1, round(5 * cell / 7))
    return 8 * cell + max(7 * t - 5 * cell, 0)


def lockup_stacked(c, H):
    """Stacked: mark on top, VECTOLYN / CLOUD below, centred. Width about 9H/4."""
    m = H / 8
    u = 5 * m / 7 * 0.62
    _, w1 = vec_text("VECTOLYN", 0, 0, u)
    _, w2 = vec_text("CLOUD", 0, 0, u)
    W = max(w1, H)
    body = mark(c, (W - H) / 2, 0, H)
    y1 = H + 4 * m
    d1, _ = vec_text("VECTOLYN", (W - w1) / 2, y1, u)
    d2, _ = vec_text("CLOUD", (W - w2) / 2, y1 + 9 * u, u)
    return body + ga.p(d1[None], c["text"]) + ga.p(d2[None], c["prim"]), W, y1 + 16 * u


def svg(w, h, body, title, bg=None, defs=""):
    rect = f'<rect width="{w:.0f}" height="{h:.0f}" fill="{bg}"/>' if bg and bg != "none" else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:.0f} {h:.0f}" width="{w:.0f}" height="{h:.0f}" '
            f'role="img" aria-label="{title}"><title>{title}</title>{("<defs>" + defs + "</defs>") if defs else ""}'
            f'{rect}{body}</svg>')


# ---------------------------------------------------------------- Voxel mark
VOX = ga.BRAND["vox"]


def voxel(x0, y0, s, pal, uid, depth=0.55, shadow=True, glow=True):
    """The 8x8 grid as blocks. Depth runs diagonally to the upper right (45 degrees), light from the upper left:
    top faces light, right flanks dark, front faces with a slight gradient, light edge on top."""
    cells = {(c, r): v for r, row in enumerate(ga.MARK_A) for c, v in enumerate(row) if v != "."}
    k = s * depth * 0.7071
    dx, dy = k, -k
    P = lambda polys: "".join("M" + "L".join(f"{_num(px)} {_num(py)}" for px, py in p) + "Z" for p in polys)
    tops, rights, fronts, edges = ({"X": [], "G": []} for _ in range(4))
    shad = []
    for (c, r), v in cells.items():
        X, Y = x0 + c * s, y0 + r * s
        fronts[v].append([(X, Y), (X + s, Y), (X + s, Y + s), (X, Y + s)])
        if (c, r - 1) not in cells:
            tops[v].append([(X, Y), (X + s, Y), (X + s + dx, Y + dy), (X + dx, Y + dy)])
            edges[v].append([(X, Y), (X + s, Y), (X + s, Y + max(1.5, s * 0.05)), (X, Y + max(1.5, s * 0.05))])
        if (c + 1, r) not in cells:
            rights[v].append([(X + s, Y), (X + s + dx, Y + dy), (X + s + dx, Y + s + dy), (X + s, Y + s)])
        so = s * 0.5
        shad.append([(px + so, py + so) for px, py in [(X, Y), (X + s, Y), (X + s, Y + s), (X, Y + s)]])
        shad.append([(px + so + dx, py + so + dy) for px, py in [(X, Y), (X + s, Y), (X + s, Y + s), (X, Y + s)]])
    st = lambda col: f' stroke="{col}" stroke-width="0.75" stroke-linejoin="miter"'
    defs = (f'<linearGradient id="vf{uid}" x1="0" y1="{y0}" x2="0" y2="{y0 + 5 * s}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{pal["cf"]}"/><stop offset="1" stop-color="{pal["cf2"]}"/></linearGradient>'
            f'<linearGradient id="vg{uid}" x1="0" y1="{y0 + 6 * s}" x2="0" y2="{y0 + 8 * s}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{pal["gf"]}"/><stop offset="1" stop-color="{pal["gf2"]}"/></linearGradient>')
    out = ""
    if glow:
        cx, cy = x0 + 4 * s, y0 + 3 * s
        defs += (f'<radialGradient id="gl{uid}" cx="{cx}" cy="{cy}" r="{6.5 * s}" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{pal["cf"]}" stop-opacity=".16"/>'
                 f'<stop offset=".55" stop-color="{pal["cf"]}" stop-opacity=".05"/>'
                 f'<stop offset="1" stop-color="{pal["cf"]}" stop-opacity="0"/></radialGradient>'
                 f'<radialGradient id="gg{uid}" cx="{x0 + 4 * s}" cy="{y0 + 7.4 * s}" r="{2.6 * s}" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{pal["gf"]}" stop-opacity=".22"/>'
                 f'<stop offset="1" stop-color="{pal["gf"]}" stop-opacity="0"/></radialGradient>')
        out += f'<circle cx="{cx}" cy="{cy}" r="{6.5 * s}" fill="url(#gl{uid})"/>'
        out += f'<circle cx="{x0 + 4 * s}" cy="{y0 + 7.4 * s}" r="{2.6 * s}" fill="url(#gg{uid})"/>'
    if shadow:
        out += f'<path d="{P(shad)}" fill="{pal["sh"]}" fill-opacity=".85"/>'
    for v, (t, r_, e) in {"X": ("ct", "cr", "ce"), "G": ("gt", "gr", "ge")}.items():
        out += f'<path d="{P(rights[v])}" fill="{pal[r_]}"{st(pal[r_])}/>'
        out += f'<path d="{P(tops[v])}" fill="{pal[t]}"{st(pal[t])}/>'
    out += f'<path d="{P(fronts["X"])}" fill="url(#vf{uid})" stroke="url(#vf{uid})" stroke-width="0.75"/>'
    out += f'<path d="{P(fronts["G"])}" fill="url(#vg{uid})" stroke="url(#vg{uid})" stroke-width="0.75"/>'
    out += f'<path d="{P(edges["X"])}" fill="{pal["ce"]}" fill-opacity=".7"/>'
    out += f'<path d="{P(edges["G"])}" fill="{pal["ge"]}" fill-opacity=".7"/>'
    return out, defs


def grid_floor(x, y, w, h, pitch, col, uid, cx, cy, r, op=0.55):
    """Fine block grid, faded out radially (a hint of a build plane)."""
    lines = "".join(f"M{x + i * pitch} {y}v{h}" for i in range(int(w // pitch) + 1))
    lines += "".join(f"M{x} {y + j * pitch}h{w}" for j in range(int(h // pitch) + 1))
    defs = (f'<radialGradient id="gm{uid}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#fff" stop-opacity="{op}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/>'
            f'</radialGradient><mask id="mk{uid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="url(#gm{uid})"/></mask>')
    return f'<path d="{lines}" stroke="{col}" stroke-width="1" fill="none" mask="url(#mk{uid})"/>', defs


TXT = {"wght": 400}


def atk(s, size, x, y, col, w=400, anchor="start", track=0.0):
    d, wd = ga.text("atkinson", s, size, x, y, {"wght": w}, track=track, anchor=anchor)
    return ga.p(d, col), wd


def mono_t(s, size, x, y, col):
    d, wd = ga.text("jetbrains", s, size, x, y, TXT)
    return ga.p(d, col), wd


URL = ga.BRAND["url"]
FACTS = ga.BRAND["facts"]


def fact_row(x, y, size, col, sq_col, gap=22):
    out, cx = "", x
    for i, f in enumerate(FACTS):
        if i:
            sq = round(size * 0.32)
            out += f'<rect x="{cx + gap / 2 - sq / 2:.1f}" y="{y - size * 0.36 - sq / 2:.1f}" width="{sq}" height="{sq}" fill="{sq_col}"/>'
            cx += gap
        t, w = atk(f, size, cx, y, col, 400)
        out += t
        cx += w
    return out, cx - x


def social_block():
    W, H = 1280, 640
    c = DARK
    s = 50
    vx, vy = 742, 132
    floor, fd = grid_floor(560, 0, 720, 640, 50, "#2A2440", "s", vx + 4 * s, vy + 4 * s, 420, 0.9)
    vox, vd = voxel(vx, vy, s, VOX["dark"], "s")
    body = floor + vox
    u = 9.6
    t1, w1 = word("VECTOLYN", 80, 150, u, c["text"])
    t2, _ = word("CLOUD", 80, 150 + 10 * u, u, c["prim"])
    body += t1 + t2
    t, _ = word("Run your own", 80, 354, 3, c["text"])
    body += t
    t, _ = word("Minecraft network.", 80, 399, 3, c["text"])
    body += t
    body += f'<rect x="80" y="476" width="{w1:.0f}" height="1" fill="{C["obsidian3"]}"/>'
    fr, _ = fact_row(80, 516, 17, c["muted"], c["acc"])
    body += fr
    t, _ = mono_t(URL, 17, 80, 568, C["subtle_d"])
    body += t
    return svg(W, H, body, "VectolynCloud social preview", c["bg"], fd + vd)


def release_block(version="v0.2.0-dev", name="Stage 2: cloud core", date="30 Oct 2026"):
    W, H = 1280, 320
    c = DARK
    s = 31
    vx, vy = 968, 44
    floor, fd = grid_floor(760, 0, 520, 320, 31, "#2A2440", "r", vx + 4 * s, vy + 4 * s, 300, 0.9)
    vox, vd = voxel(vx, vy, s, VOX["dark"], "r")
    body = floor + vox
    lk, _ = lockup(c, 32, "product")
    body += f'<g transform="translate(80 52)">{lk}</g>'
    u = 12
    cols = [c["acc"] if ch == "." else c["text"] for ch in version]
    d, wv = vec_text(version, 76, 118, u, colors=cols)
    for col, dd in d.items():
        body += ga.p(dd, col)
    t, wn = atk(name, 24, 80, 252, c["text"], 700)
    body += t
    body += f'<rect x="{80 + wn + 18:.0f}" y="{252 - 13}" width="6" height="6" fill="{c["acc"]}"/>'
    t, _ = atk(date, 24, 80 + wn + 42, 252, c["muted"], 400)
    body += t
    return svg(W, H, body, f"VectolynCloud {version} release", c["bg"], fd + vd)


def readme_header(c, mode):
    W, H = 1280, 280
    s = 21
    vx, vy = 1040, 58
    floor, fd = grid_floor(820, 0, 460, 280, 21, "#2A2440" if mode == "dark" else C["quartz3"], "h" + mode,
                           vx + 4 * s, vy + 4 * s, 240, 0.9)
    vox, vd = voxel(vx, vy, s, VOX[mode], "h" + mode)
    body = floor + vox
    lk, lw = lockup(c, 72, "product")
    body += f'<g transform="translate(72 70)">{lk}</g>'
    t, _ = word("Run your own Minecraft network.", 72, 180, 3, c["text"])
    body += t
    fr, _ = fact_row(72, 236, 16, c["muted"], c["acc"])
    body += fr
    return svg(W, H, body, "VectolynCloud", c["bg"], fd + vd)


def server_icon64(c=DARK):
    """Server icon 64 x 64: pixel voxel, cell 6 px, depth 2 px, everything on whole pixels."""
    cells = {(cc, r): v for r, row in enumerate(ga.MARK_A) for cc, v in enumerate(row) if v != "."}
    s, d, x0, y0 = 6, 2, 7, 9
    pal = VOX["dark"]
    out = f'<rect width="64" height="64" fill="{C["obsidian"]}"/>'
    out += f'<rect x="1" y="1" width="62" height="62" fill="{C["obsidian2"]}"/>'
    out += f'<rect x="2" y="2" width="60" height="60" fill="{C["obsidian"]}"/>'
    rects = {"sh": [], "X_r": [], "X_t": [], "G_r": [], "G_t": [], "X": [], "G": []}
    for (cc, r), v in cells.items():
        X, Y = x0 + cc * s, y0 + r * s
        rects["sh"].append((X + 3, Y + 3, s, s))
        for k in range(1, d + 1):  # depth as a staircase: 1 px to the upper right per step
            if (cc + 1, r) not in cells:
                rects[v + "_r"].append((X + s + k - 1, Y - k + 1, 1, s))
            if (cc, r - 1) not in cells:
                rects[v + "_t"].append((X + k, Y - k, s, 1))
        rects[v].append((X, Y, s, s))
    col = {"sh": pal["sh"], "X_r": pal["cr"], "X_t": pal["ct"], "G_r": pal["gr"], "G_t": pal["gt"],
           "X": pal["cf"], "G": pal["gf"]}
    for k in ("sh", "X_r", "G_r", "X_t", "G_t", "X", "G"):
        out += "".join(f'<rect x="{a}" y="{b}" width="{w}" height="{h}" fill="{col[k]}"/>' for a, b, w, h in rects[k])
    return f'<g shape-rendering="crispEdges">{out}</g>'


# ---------------------------------------------------------------- Text layers over the Cycles renders
# Background and mark come from render_mark.py; only the typography is done here, as transparent paths.

def social_text():
    c = DARK
    u = 10
    body = ""
    t1, w1 = word("VECTOLYN", 80, 150, u, c["text"])
    t2, _ = word("CLOUD", 80, 150 + 10 * u, u, c["prim"])
    body += t1 + t2
    t, _ = word("Run your own", 80, 354, 3, c["text"])
    body += t
    t, _ = word("Minecraft network.", 80, 399, 3, c["text"])
    body += t
    body += f'<rect x="80" y="476" width="{w1:.0f}" height="1" fill="#FFFFFF" fill-opacity=".14"/>'
    fr, _ = fact_row(80, 516, 17, c["muted"], c["acc"])
    body += fr
    t, _ = mono_t(URL, 17, 80, 568, C["subtle_d"])
    return svg(1280, 640, body + t, "VectolynCloud social preview, text layer")


def wordmark(c, x, y, u):
    """VECTOLYN + CLOUD as one word, without the mark (cases 2/3: the mark stands separately in the image)."""
    d1, w1 = vec_text("VECTOLYN", x, y, u)
    d2, w2 = vec_text("CLOUD", x + w1 + u, y, u)
    return ga.p(d1[None], c["text"]) + ga.p(d2[None], c["prim"]), w1 + u + w2


def readme_text(c):
    wm, _ = wordmark(c, 72, 78, 6)
    body = wm
    t, _ = word("Run your own Minecraft network.", 72, 180, 3, c["text"])
    body += t
    fr, _ = fact_row(72, 236, 16, c["muted"], c["acc"])
    return svg(1280, 280, body + fr, "VectolynCloud README header, text layer")


# ---------------------------------------------------------------- Release banner system
# The background is rendered once (render_mark.py, job release); per release only this text layer changes.
# Everything is Pixelify Sans 700 as paths, sizes in whole font pixels (u) so the steps stay crisp.
import re as _re

REL = dict(
    W=1280, H=320, x=80, text_right=860,          # text column; the 3D mark sits to its right
    wordmark_u=3, version_u=(12, 10, 8, 6), title_u=3, date_u=3, badge_u=3,
    gap_wm_version=20, gap_version_title=24, gap_badge=12, sep=18,
    title_max_chars=48, ellipsis="…",
)
BADGES = [  # (rule on the version suffix, label, fill colour); text colour is always obsidian. Same rules as
    # tools/release-banner (ReleaseVersion): -dev[.N] (the only suffix below 1.0.0), -alpha[.N], -beta[.N], -rc[.N],
    # -lts without counter, no suffix = stable without badge; anything else is no release tag.
    (r"^dev(\.(0|[1-9]\d*))?$", "PRE-RELEASE", "#9EA3B8"),
    (r"^alpha(\.(0|[1-9]\d*))?$", "ALPHA", "#FF6B6B"),
    (r"^beta(\.(0|[1-9]\d*))?$", "BETA", "#FFD23F"),
    (r"^rc(\.(0|[1-9]\d*))?$", "RC", "#00E5FF"),
    (r"^lts$", "LTS", "#5BD98A"),  # color.success (dark)
]
MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()


def release_badge(version):
    m = _re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?", version, _re.ASCII)
    if not m:
        raise ValueError(f"not a release tag: {version}")
    pre = m.group(4)
    if not pre:
        if m.group(1) == "0":
            raise ValueError(f"a 0.x version may only be released with -dev: {version}")
        return None
    if _re.fullmatch(r"lts\.[0-9]+", pre):
        raise ValueError(f"-lts takes no counter, the patch version counts LTS releases: {version}")
    for rx, label, col in BADGES:
        if _re.fullmatch(rx, pre, _re.ASCII):
            if m.group(1) == "0" and label != "PRE-RELEASE":
                raise ValueError(f"a 0.x version may only be released with -dev: {version}")
            return label, col
    raise ValueError(f"unknown suffix -{pre}: {version}")


def release_date(iso):
    y, mo, d = (int(v) for v in iso.split("-"))
    return f"{MONTHS[mo - 1]} {d}, {y}"


def badge(label, col, x, y, u):
    """Pixel badge: area with corners cut by one font pixel each, obsidian text, padding 2u horizontal, 1u vertical."""
    d, w = vec_text(label, 0, 0, u)
    pw, ph = w + 4 * u, 9 * u
    pts = [(u, 0), (pw - u, 0), (pw - u, u), (pw, u), (pw, ph - u), (pw - u, ph - u), (pw - u, ph), (u, ph),
           (u, ph - u), (0, ph - u), (0, u), (u, u)]
    shape = "M" + "L".join(f"{x + a:.0f} {y + b:.0f}" for a, b in pts) + "Z"
    dt, _ = vec_text(label, x + 2 * u, y + u, u)
    return (f'<g id="slot-badge" data-label="{label}"><path id="badge-bg" d="{shape}" fill="{col}"/>'
            f'{ga.p(dt[None], C["obsidian"])}</g>'), pw, ph


def release_text(version="v0.2.0-dev", title="Stage 2: cloud core", date="2026-10-30"):
    """Text layer 1280 x 320 for the release banner. version: a release tag with v (release_badge rules). title may be empty."""
    c, R = DARK, REL
    x, maxw = R["x"], R["text_right"] - R["x"]
    # Version: largest u at which the line fits
    for vu in R["version_u"]:
        _, vw = vec_text(version, 0, 0, vu)
        if vw <= maxw:
            break
    # Title: at most 48 characters, then cut at a word end; additionally cut to the column width
    date_s = release_date(date)
    _, dw = vec_text(date_s, 0, 0, R["date_u"])
    title = (title or "").strip()
    if len(title) > R["title_max_chars"]:
        title = title[:R["title_max_chars"]].rsplit(" ", 1)[0].rstrip(" ,.:;-") + R["ellipsis"]
    room = maxw - (dw + 2 * R["sep"] if title else 0)
    while title and vec_text(title, 0, 0, R["title_u"])[1] > room:
        core = title.rstrip(R["ellipsis"]).rsplit(" ", 1)
        title = (core[0] if len(core) > 1 else core[0][:-1]).rstrip(" ,.:;-") + R["ellipsis"]
    # Vertically centred block: wordmark, version, title/date line
    hw, hv, ht = 7 * R["wordmark_u"], 7 * vu, 7 * R["title_u"]
    block = hw + R["gap_wm_version"] + hv + R["gap_version_title"] + ht
    y0 = round((R["H"] - block) / 2)
    yv = y0 + hw + R["gap_wm_version"]
    yt = yv + hv + R["gap_version_title"]
    body = ""
    wm, ww = wordmark(c, x, y0, R["wordmark_u"])
    body += f'<g id="slot-wordmark">{wm}</g>'
    b = release_badge(version)
    if b:
        bg, _, bh = badge(b[0], b[1], x + ww + R["gap_badge"], y0 + hw / 2 - 9 * R["badge_u"] / 2, R["badge_u"])
        body += bg
    core_len = len(version.split("-")[0])
    cols = [c["acc"] if ch == "." and i < core_len else c["text"] for i, ch in enumerate(version)]
    d, _ = vec_text(version, x, yv, vu, colors=cols)
    body += '<g id="slot-version" data-u="%d">' % vu + "".join(ga.p(dd, col) for col, dd in d.items()) + "</g>"
    cx = x
    if title:
        dt, tw = vec_text(title, x, yt, R["title_u"])
        body += f'<g id="slot-title">{ga.p(dt[None], c["text"])}</g>'
        cx = x + tw + R["sep"] - 3
        body += f'<rect x="{cx:.0f}" y="{yt + 9}" width="6" height="6" fill="{c["acc"]}"/>'
        cx += 3 + R["sep"]
    dd, _ = vec_text(date_s, cx, yt, R["date_u"])
    body += f'<g id="slot-date">{ga.p(dd[None], c["muted"])}</g>'
    return svg(R["W"], R["H"], body, f"VectolynCloud {version} release, text layer")


def release_template():
    """Template with named slots as SVG text (for the CI implementation); dimensions as release_text at u=12."""
    R = REL
    y0 = round((R["H"] - (21 + 20 + 84 + 24 + 21)) / 2)
    yv, yt = y0 + 41, y0 + 41 + 84 + 24
    f = "font-family=\"Pixelify Sans\" font-weight=\"700\""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 320" width="1280" height="320">
<title>VectolynCloud release banner template</title>
<desc>Text layer over png/release-banner-background.png. Pixelify Sans 700, outline to paths before export.
One font pixel u = 0.09143 em. Tracking -0.0286 em (one font pixel between letters). Cap height 7u.
y values are cap tops. Text column x 80 to 860; the 3D mark owns the area right of it.
Block is centered vertically: wordmark (u 3) + 20 + version (7u) + 24 + title/date (u 3).
version: u 12, falls back to 10, 8, 6 until it fits 780 px. Points of MAJOR.MINOR.PATCH in gold #FFD23F, rest #ECEEF4.
title: first line of the tag message, u 3, #ECEEF4, max 48 characters, cut at a word end with an ellipsis,
then shortened word by word if title + date exceed 780 px. Empty title: the line holds only the date.
date: "Oct 30, 2026", u 3, #9EA3B8, after a 6 x 6 gold square with 18 px on each side.
badge: by version suffix -dev[.N] PRE-RELEASE #9EA3B8, -alpha[.N] ALPHA #FF6B6B, -beta[.N] BETA #FFD23F, -rc[.N] RC #00E5FF,
-lts LTS #5BD98A; stable (no suffix): no badge. 0.x versions are -dev only; any other suffix is no release tag.
u 3, text #14111F, padding 2u x 1u (height 9u), corners cut by 1u, 12 px right of the wordmark, centered on its cap height.</desc>
<rect id="safe-area" x="80" y="{y0}" width="780" height="{320 - 2 * y0}" fill="none" stroke="#FF00FF" stroke-dasharray="4 4"/>
<text id="slot-wordmark" x="80" y="{y0 + 21}" {f} font-size="32.8" letter-spacing="-0.94" fill="#ECEEF4">VECTOLYN<tspan fill="#00E5FF">CLOUD</tspan></text>
<g id="slot-badge"><rect id="badge-bg" x="0" y="{y0 - 3}" width="0" height="27" fill="#FFD23F"/><text x="0" y="{y0 + 21}" {f} font-size="32.8" letter-spacing="-0.94" fill="#14111F">BETA</text></g>
<text id="slot-version" x="80" y="{yv + 84}" {f} font-size="131.2" letter-spacing="-3.75" fill="#ECEEF4">v1.0.0</text>
<text id="slot-title" x="80" y="{yt + 21}" {f} font-size="32.8" letter-spacing="-0.94" fill="#ECEEF4">Stage 2: cloud core</text>
<rect id="slot-separator" x="0" y="{yt + 9}" width="6" height="6" fill="#FFD23F"/>
<text id="slot-date" x="0" y="{yt + 21}" {f} font-size="32.8" letter-spacing="-0.94" fill="#9EA3B8">Oct 30, 2026</text>
</svg>'''
