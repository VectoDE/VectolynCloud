"""Own pixel redraw of the Minecraft default font (ASCII part) with the advance widths of the
game font. Contains no Mojang files: every glyph is noted here by hand as a bitmap.

Advance = glyph width + 1, space 4, bold +1 (glyph drawn a second time, shifted 1 px to the right).
Shadow: 1 px right/down, colour (rgb & 0xFCFCFC) >> 2.
Rows 0-6 sit above the baseline, row 7 is the descender (g, j, p, q, y, comma).
"""

R = {
    # Capitals, 5 wide (I: 3)
    "A": ".XXX.|X...X|XXXXX|X...X|X...X|X...X|X...X",
    "B": "XXXX.|X...X|XXXX.|X...X|X...X|X...X|XXXX.",
    "C": ".XXX.|X...X|X....|X....|X....|X...X|.XXX.",
    "D": "XXXX.|X...X|X...X|X...X|X...X|X...X|XXXX.",
    "E": "XXXXX|X....|XXX..|X....|X....|X....|XXXXX",
    "F": "XXXXX|X....|XXX..|X....|X....|X....|X....",
    "G": ".XXXX|X....|X..XX|X...X|X...X|X...X|.XXXX",
    "H": "X...X|X...X|XXXXX|X...X|X...X|X...X|X...X",
    "I": "XXX|.X.|.X.|.X.|.X.|.X.|XXX",
    "J": "....X|....X|....X|....X|....X|X...X|.XXX.",
    "K": "X...X|X..X.|XXX..|X..X.|X...X|X...X|X...X",
    "L": "X....|X....|X....|X....|X....|X....|XXXXX",
    "M": "X...X|XX.XX|X.X.X|X...X|X...X|X...X|X...X",
    "N": "X...X|XX..X|X.X.X|X..XX|X...X|X...X|X...X",
    "O": ".XXX.|X...X|X...X|X...X|X...X|X...X|.XXX.",
    "P": "XXXX.|X...X|XXXX.|X....|X....|X....|X....",
    "Q": ".XXX.|X...X|X...X|X...X|X...X|X..X.|.XX.X",
    "R": "XXXX.|X...X|XXXX.|X...X|X...X|X...X|X...X",
    "S": ".XXXX|X....|.XXX.|....X|....X|X...X|.XXX.",
    "T": "XXXXX|..X..|..X..|..X..|..X..|..X..|..X..",
    "U": "X...X|X...X|X...X|X...X|X...X|X...X|.XXX.",
    "V": "X...X|X...X|X...X|X...X|X...X|.X.X.|..X..",
    "W": "X...X|X...X|X...X|X...X|X.X.X|XX.XX|X...X",
    "X": "X...X|.X.X.|..X..|.X.X.|X...X|X...X|X...X",
    "Y": "X...X|.X.X.|..X..|..X..|..X..|..X..|..X..",
    "Z": "XXXXX|....X|...X.|..X..|.X...|X....|XXXXX",
    # Lower case, 5 wide (f, k: 4; i: 1; l: 2; t: 3)
    "a": ".....|.....|.XXX.|....X|.XXXX|X...X|.XXXX",
    "b": "X....|X....|X.XX.|XX..X|X...X|X...X|XXXX.",
    "c": ".....|.....|.XXX.|X...X|X....|X...X|.XXX.",
    "d": "....X|....X|.XX.X|X..XX|X...X|X...X|.XXXX",
    "e": ".....|.....|.XXX.|X...X|XXXXX|X....|.XXXX",
    "f": "..XX|.X..|XXXX|.X..|.X..|.X..|.X..",
    "g": ".....|.....|.XXXX|X...X|X...X|.XXXX|....X|XXXX.",
    "h": "X....|X....|X.XX.|XX..X|X...X|X...X|X...X",
    "i": "X|.|X|X|X|X|X",
    "j": "....X|.....|....X|....X|....X|X...X|X...X|.XXX.",
    "k": "X...|X...|X..X|X.X.|XX..|X.X.|X..X",
    "l": "X.|X.|X.|X.|X.|X.|.X",
    "m": ".....|.....|XX.X.|X.X.X|X.X.X|X...X|X...X",
    "n": ".....|.....|XXXX.|X...X|X...X|X...X|X...X",
    "o": ".....|.....|.XXX.|X...X|X...X|X...X|.XXX.",
    "p": ".....|.....|X.XX.|XX..X|X...X|XXXX.|X....|X....",
    "q": ".....|.....|.XX.X|X..XX|X...X|.XXXX|....X|....X",
    "r": ".....|.....|X.XX.|XX..X|X....|X....|X....",
    "s": ".....|.....|.XXXX|X....|.XXX.|....X|XXXX.",
    "t": ".X.|.X.|XXX|.X.|.X.|.X.|..X",
    "u": ".....|.....|X...X|X...X|X...X|X...X|.XXXX",
    "v": ".....|.....|X...X|X...X|X...X|.X.X.|..X..",
    "w": ".....|.....|X...X|X...X|X.X.X|X.X.X|.XXXX",
    "x": ".....|.....|X...X|.X.X.|..X..|.X.X.|X...X",
    "y": ".....|.....|X...X|X...X|X...X|.XXXX|....X|XXXX.",
    "z": ".....|.....|XXXXX|...X.|..X..|.X...|XXXXX",
    "ä": ".X.X.|.....|.XXX.|....X|.XXXX|X...X|.XXXX",
    "ö": ".X.X.|.....|.XXX.|X...X|X...X|X...X|.XXX.",
    "ü": ".X.X.|.....|X...X|X...X|X...X|X...X|.XXXX",
    # Digits, 5 wide
    "0": ".XXX.|X...X|X..XX|X.X.X|XX..X|X...X|.XXX.",
    "1": "..X..|.XX..|..X..|..X..|..X..|..X..|XXXXX",
    "2": ".XXX.|X...X|....X|..XX.|.X...|X...X|XXXXX",
    "3": ".XXX.|X...X|....X|..XX.|....X|X...X|.XXX.",
    "4": "...XX|..X.X|.X..X|X...X|XXXXX|....X|....X",
    "5": "XXXXX|X....|XXXX.|....X|....X|X...X|.XXX.",
    "6": "..XX.|.X...|X....|XXXX.|X...X|X...X|.XXX.",
    "7": "XXXXX|X...X|....X|...X.|..X..|..X..|..X..",
    "8": ".XXX.|X...X|X...X|.XXX.|X...X|X...X|.XXX.",
    "9": ".XXX.|X...X|X...X|.XXXX|....X|...X.|.XX..",
    # Punctuation
    ".": ".|.|.|.|.|X|X",
    ",": ".|.|.|.|.|X|X|X",
    ":": ".|X|X|.|.|X|X",
    "!": "X|X|X|X|X|.|X",
    "'": "X|X|.|.|.|.|.",
    "|": "X|X|X|X|X|X|X|X",
    "-": ".....|.....|.....|XXXXX|.....|.....|.....",
    "/": "....X|....X|...X.|..X..|.X...|X....|X....",
    "[": "XXX|X..|X..|X..|X..|X..|XXX",
    "]": "XXX|..X|..X|..X|..X|..X|XXX",
    "(": "..X|.X.|X..|X..|X..|.X.|..X",
    ")": "X..|.X.|..X|..X|..X|.X.|X..",
    "&": "..X..|.X.X.|..X..|.XX.X|X..X.|X..X.|.XX.X",
    ">": "X...|.X..|..X.|...X|..X.|.X..|X...",
    # Added for tab list and MOTD (ASCII shapes redrawn, advance = width + 1)
    "?": ".XXX.|X...X|....X|...X.|..X..|.....|..X..",
    "+": ".....|..X..|..X..|XXXXX|..X..|..X..|.....",
    "=": ".....|.....|XXXXX|.....|.....|XXXXX|.....",
    "_": ".....|.....|.....|.....|.....|.....|.....|XXXXX",
    ";": ".|X|X|.|.|X|X|X",
    "<": "...X|..X.|.X..|X...|.X..|..X.|...X",
    "%": "X...X|X..X.|...X.|..X..|.X...|.X..X|X...X",
    "#": ".X.X.|.X.X.|XXXXX|.X.X.|XXXXX|.X.X.|.X.X.",
    "@": ".XXXX.|X....X|X.XX.X|X.XX.X|X.XXXX|X.....|.XXXX.",
    '"': "X.X|X.X|...|...|...|...|...",
    # Non-ASCII: « » advance 7 (ascii.png), • advance 3; shapes redrawn
    "»": "......|X..X..|.X..X.|..X..X|.X..X.|X..X..|......",
    "«": "......|..X..X|.X..X.|X..X..|.X..X.|..X..X|......",
    "•": "..|..|..|XX|XX|..|..",
    # ■ is in ascii.png; shape and advance not verified here
    "■": "....|....|XXXX|XXXX|XXXX|XXXX|....",
}
APPROX = set("»«•■")
GLYPH = {k: v.split("|") for k, v in R.items()}

# Own glyphs that only exist with the Vectolyn resource pack (bitmap provider, colours from the texture;
# white text colour leaves them unchanged). Code points from the private use area.
PACK = {
    # Mark as initial (replaces the V): V body at cap height (7 rows), gap 1, gold block 2 x 2 as a
    # descender (rows 8 and 9). Height 10, ascent 7. Own design for the resource pack, not checked in the game.
    "": dict(rows=["XX....XX", "XX....XX", ".XX..XX.", ".XX..XX.", "..XXXX..", "...XX...", "...XX...",
                         "........", "...GG...", "...GG..."], colors={"X": "#00E5FF", "G": "#FFD23F"}, ascent=7),
}


def flags(s):
    """Segment (text, colour[, bold[, strikethrough]]) -> (bold, strikethrough)."""
    return (len(s) > 2 and bool(s[2])), (len(s) > 3 and bool(s[3]))


def advance(ch, bold=False):
    if ch == " ":
        return 4 + (1 if bold else 0)
    if ch in PACK:
        return len(PACK[ch]["rows"][0]) + 1 + (1 if bold else 0)
    return len(GLYPH[ch][0]) + 1 + (1 if bold else 0)


def width(segs):
    """segs: [(text, hex, bold?, strikethrough?)] -> width in GUI pixels (sum of the advances, as in the game)."""
    return sum(advance(ch, flags(s)[0]) for s in segs for ch in s[0])


def shadow(hexcol):
    v = int(hexcol.lstrip("#"), 16)
    v = (v & 0xFCFCFC) >> 2
    return f"#{v:06X}"


def draw(segs, x, y, out, clip=None, mode="text", strike_y=3.5):
    """Draws text (mode text) or only its shadow (mode shadow) into out: key -> set((x, y)).
    Strikethrough: line 1 px high, top edge y + strike_y (current clients 3.5, i.e. y + 4.5 - 1, from x - 1;
    1.8: 3, from x), to x + advance, with shadow. Pack glyphs keep their colours, their shadow is the
    silhouette in the shadow colour of white. Returns the end position."""
    cx = x
    for s in segs:
        text, col = s[0], s[1]
        bold, strike = flags(s)
        colour, off = (shadow(col), 1) if mode == "shadow" else (col, 0)
        for ch in text:
            adv = advance(ch, bold)
            if clip is not None and cx + adv - 1 > clip:
                return cx
            if ch in PACK:
                g = PACK[ch]
                top = y + 7 - g["ascent"]
                for r, row in enumerate(g["rows"]):
                    for c, v in enumerate(row):
                        if v != ".":
                            cc = shadow("#FFFFFF") if mode == "shadow" else g["colors"][v]
                            for bx in ((0, 1) if bold else (0,)):
                                out.setdefault(cc, set()).add((cx + c + bx + off, top + r + off))
            elif ch != " ":
                for r, row in enumerate(GLYPH[ch]):
                    for c, v in enumerate(row):
                        if v == "X":
                            for bx in ((0, 1) if bold else (0,)):
                                out.setdefault(colour, set()).add((cx + c + bx + off, y + r + off))
            if strike:
                x0 = cx - 1 if strike_y == 3.5 else cx
                out.setdefault(("strike", colour, strike_y), set()).update(
                    (xx + off, y + off) for xx in range(x0, cx + adv))
            cx += adv
    return cx


def layer_svg(layers):
    """layers: list of (key, cell set) in drawing order -> SVG paths (1 px grid).
    Key ("strike", colour, dy): strikethrough line, cell (x, y) sits at the line top y + dy."""
    out = []
    for key, cells in layers:
        if not cells:
            continue
        col, dy = (key[1], key[2]) if isinstance(key, tuple) else (key, 0)
        rows = {}
        for x, y in cells:
            rows.setdefault(y, set()).add(x)
        d = []
        for y in sorted(rows):
            xs = sorted(rows[y])
            yy = f"{y + dy:g}"
            start = prev = xs[0]
            for xx in xs[1:] + [None]:
                if xx is not None and xx == prev + 1:
                    prev = xx
                    continue
                d.append(f"M{start} {yy}h{prev + 1 - start}v1h{-(prev + 1 - start)}z")
                if xx is not None:
                    start = prev = xx
        out.append(f'<path d="{"".join(d)}" fill="{col}"/>')
    return "".join(out)


def text_svg(segs, x, y, clip=None, shadow_on=True, strike_y=3.5):
    """Text as SVG: first the shadow of the whole line, then the line (as in the game)."""
    sh, tx = {}, {}
    if shadow_on:
        draw(segs, x, y, sh, clip, "shadow", strike_y)
    end = draw(segs, x, y, tx, clip, "text", strike_y)
    return layer_svg(list(sh.items()) + list(tx.items())), end
