"""Game UI views in GUI pixels (scale 1), version 3: server list with neighbouring entries, selection frame and
blurred panorama, tab list after PlayerTabOverlay, Bedrock target design. No Mojang files: font (mcfont),
heads, ping icon, icons and backgrounds are our own drawings."""
import base64
import io
import random

from PIL import Image, ImageDraw, ImageFilter

import mcfont as F
import mcmock as MM

WHITE, GRAY, GRAY_D, BLACK = "#FFFFFF", "#AAAAAA", "#555555", "#000000"
YELLOW = "#FFFF55"


def rect(x, y, w, h, fill, op=None):
    o = f' fill-opacity="{op}"' if op is not None else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}"{o}/>'


def svg(w, h, body, label, x0=0, y0=0):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0} {y0} {w} {h}" width="{w}" height="{h}" '
            f'shape-rendering="crispEdges" role="img" aria-label="{label}"><title>{label}</title>{body}</svg>')


def png_uri(img):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


# ---------------------------------------------------------------- Backgrounds (own drawings)

def landscape(w, h, seed=7, k=1):
    """Blocky landscape in flat colours (no textures). k: pixels per GUI pixel."""
    rnd = random.Random(seed)
    img = Image.new("RGB", (w * k, h * k))
    d = ImageDraw.Draw(img)
    top, bot = (58, 70, 128), (150, 120, 170)
    for y in range(h * k):
        t = y / (h * k)
        d.line([(0, y), (w * k, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    b = 12 * k  # block grid
    hor = int(h * k * 0.55)
    cols = (w * k) // b + 2
    hgt = [hor]
    for _ in range(cols):
        hgt.append(max(hor - 5 * b, min(hor + 3 * b, hgt[-1] + rnd.choice([-b, 0, 0, b]))))
    for i in range(cols):
        x = i * b
        y = hgt[i]
        d.rectangle([x, y, x + b - 1, y + b // 3], fill=(92, 150, 64))
        d.rectangle([x, y + b // 3, x + b - 1, y + 2 * b], fill=(121, 85, 58))
        d.rectangle([x, y + 2 * b, x + b - 1, h * k], fill=(112, 112, 118))
    for _ in range(int(w / 40) + 2):  # trees and buildings
        x = rnd.randrange(0, cols) * b
        y = hgt[x // b]
        if rnd.random() < 0.6:
            d.rectangle([x, y - 3 * b, x + b - 1, y - 1], fill=(110, 82, 50))
            d.rectangle([x - b, y - 6 * b, x + 2 * b - 1, y - 3 * b], fill=(58, 112, 48))
        else:
            ww = rnd.choice([3, 4, 5]) * b
            hh = rnd.choice([3, 4, 6]) * b
            d.rectangle([x, y - hh, x + ww - 1, y - 1], fill=rnd.choice([(196, 182, 150), (140, 100, 70), (170, 170, 176)]))
            d.rectangle([x + b, y - hh + b, x + 2 * b - 1, y - hh + 2 * b - 1], fill=(250, 210, 120))
    d.rectangle([0, int(h * k * 0.86), w * k, h * k], fill=(64, 92, 170))
    return img


def panorama_bg(w, h, k=4, dim=0.8):
    """Background of the multiplayer screen: own landscape, heavily blurred and darkened
    (current clients draw the title panorama blurred, with the dark list area on top)."""
    img = landscape(w, h, seed=3, k=k).filter(ImageFilter.GaussianBlur(radius=7 * k))
    img = Image.blend(img, Image.new("RGB", img.size, (0, 0, 0)), dim)
    return f'<image href="{png_uri(img)}" x="0" y="0" width="{w}" height="{h}" preserveAspectRatio="none" style="image-rendering:auto"/>'


def dirt_bg(w, h):
    """1.8: tiled dark dirt floor. Own 16 x 16 noise texture in earth tones, darkened as in the game (factor 0.25)."""
    rnd = random.Random(5)
    tile = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            base = rnd.choice([(134, 96, 67), (121, 85, 58), (150, 108, 74), (99, 70, 48), (115, 115, 115)])
            tile.putpixel((x, y), tuple(int(c * 0.25) for c in base))
    img = Image.new("RGB", (w, h))
    for y in range(0, h, 16):
        for x in range(0, w, 16):
            img.paste(tile, (x, y))
    return f'<image href="{png_uri(img)}" x="0" y="0" width="{w}" height="{h}" style="image-rendering:pixelated"/>'


def world_bg(w, h):
    img = landscape(w, h, seed=11, k=1)
    return f'<image href="{png_uri(img)}" x="0" y="0" width="{w}" height="{h}" style="image-rendering:pixelated"/>'


# ---------------------------------------------------------------- Icons

PING = {"on": "#4BF04B", "on_d": "#1F8A1F", "off": "#2F2F2F", "off_d": "#1A1A1A"}


def ping_icon(x, y, bars=5):
    """Connection icon 10 x 8, five bars (heights 2, 3, 4, 6, 8), each 1 px light plus 1 px shadow.
    Own redraw, shape approximated."""
    out = ""
    for i, hh in enumerate((2, 3, 4, 6, 8)):
        on = i < bars
        out += rect(x + 2 * i, y + 8 - hh, 1, hh, PING["on"] if on else PING["off"])
        out += rect(x + 2 * i + 1, y + 8 - hh + 1, 1, hh - 1, PING["on_d"] if on else PING["off_d"])
    return out


def bars_for(ms):
    return 5 if ms < 150 else 4 if ms < 300 else 3 if ms < 600 else 2 if ms < 1000 else 1


HEADS = [("#4A2F1B", "#C99A70", "#2F5FA0"), ("#D9A441", "#F0C090", "#3C7A3C"), ("#1E1E1E", "#8E5B3E", "#5A3A1A"),
         ("#7A4A2A", "#D7A17A", "#2F5FA0"), ("#B0B0B0", "#E8B896", "#4A6FB0"), ("#3B2A1A", "#B07850", "#2A2A2A"),
         ("#A33A2A", "#F2C6A0", "#3C7A3C"), ("#2B3A5A", "#C48B62", "#7A3A9A")]


def head(x, y, i):
    """8 x 8 head (own placeholder, no skins)."""
    hair, skin, eye = HEADS[i % len(HEADS)]
    o = rect(x, y, 8, 8, skin) + rect(x, y, 8, 2, hair) + rect(x, y + 2, 1, 2 + i % 2, hair) + rect(x + 7, y + 2, 1, 2, hair)
    o += rect(x + 1, y + 4, 2, 1, WHITE) + rect(x + 5, y + 4, 2, 1, WHITE)
    o += rect(x + 2, y + 4, 1, 1, eye) + rect(x + 5, y + 4, 1, 1, eye)
    o += rect(x + 3, y + 6, 2, 1, "#6A3A2A")
    return o


def placeholder_icon(letter, bg1, bg2, fg):
    """Placeholder icon 64 x 64 for other networks: area with a pixel letter (from mcfont, 6x)."""
    o = rect(0, 0, 64, 64, bg2) + rect(4, 4, 56, 56, bg1)
    g = F.GLYPH[letter]
    cw = len(g[0])
    ox = (64 - cw * 6) // 2
    for r, row in enumerate(g):
        for c, v in enumerate(row):
            if v == "X":
                o += rect(ox + c * 6 + 3, 11 + r * 6 + 3, 6, 6, "#00000066")
                o += rect(ox + c * 6, 11 + r * 6, 6, 6, fg)
    return o


# ---------------------------------------------------------------- Server list

def center(segs, area=270):
    """Centre like networks do: leading spaces (4 px) according to the advance table."""
    w = F.width(segs)
    n = max(0, round((area - w) / 2 / 4))
    return [(" " * n, WHITE)] + list(segs), n, w


def server_entry(e, left, top, modern):
    o = f'<g transform="translate({left} {top}) scale(0.5)">{e["icon"]}</g>'
    t, _ = F.text_svg([(e["name"], WHITE)], left + 35, top + 1, shadow_on=modern)
    o += t
    for i, line in enumerate(e["motd"][:2]):
        assert F.width(line) <= 271, (e["name"], F.width(line))
        t, _ = F.text_svg(line, left + 35, top + 12 + 9 * i, shadow_on=modern)
        o += t
    on, mx = e["count"]
    if modern:
        cnt = [(on, WHITE), ("/", GRAY), (mx, WHITE)]
    else:
        cnt = [(on, GRAY), ("/", GRAY_D), (mx, GRAY)]
    cw = F.width(cnt)
    t, _ = F.text_svg(cnt, left + 305 - cw - 15 - 2, top + 1, shadow_on=modern)
    o += t + ping_icon(left + 305 - 15, top, e.get("bars", 5))
    return o


def server_list(entries, selected, modern=True, label="Mehrspieler-Serverliste"):
    """Excerpt of the multiplayer list: entries 305 wide on a 36 px grid, selection frame 1 px
    (currently white when focused, 1.8 grey #808080) with a black inner area, content inset by 2 px."""
    W = 321
    H = 8 + len(entries) * 36
    left = (W - 305) // 2 + 2
    body = panorama_bg(W, H) if modern else dirt_bg(W, H)
    for i, e in enumerate(entries):
        top = 6 + i * 36
        if i == selected:
            body += rect(left - 2, top - 2, 305, 36, WHITE if modern else "#808080")
            body += rect(left - 1, top - 1, 303, 34, BLACK)
        body += server_entry(e, left, top, modern)
    return svg(W, H, body, label)


def bedrock_entry(motd1, name="Vectolyn", count=("128", "500")):
    """Bedrock server list through Geyser, approximated: own name, motd1 below it (the sub-MOTD is only shown locally,
    according to the Geyser source). Colours in Bedrock values."""
    W, H = 320, 40
    body = rect(0, 0, W, H, "#1E1E1F") + rect(2, 2, W - 4, H - 4, "#48494A") + rect(2, 2, W - 4, 1, "#6D6D6E")
    body += rect(2, H - 4, W - 4, 2, "#313233")
    t, _ = F.text_svg([(name, WHITE)], 10, 8)
    body += t
    t, _ = F.text_svg(motd1, 10, 22)
    body += t
    st = [(f"{count[0]}/{count[1]}", MM.BEDROCK["7"])]
    t, _ = F.text_svg(st, W - F.width(st) - 24, 8)
    body += t + ping_icon(W - 18, 8)
    return svg(W, H, body, "Bedrock server list, entry (approximated)")


# ---------------------------------------------------------------- Tab list (PlayerTabOverlay)

def wrap_lines(lines, maxw):
    out = []
    for segs in lines:
        segs = [s for s in segs if s[0]]
        if not segs:
            out.append([])
        elif F.width(segs) <= maxw:
            out.append(segs)
        else:
            out += MM.wrap(segs, maxw)
    return out


def tab_list(header, footer, players, modern=True, objective=None, screen=(640, 360), crop_margin=34,
             label="Spielerliste (Tab)", world=True):
    """players: [dict(name=segs, ping=ms, head=i, score=segs|None)]. Flow as in PlayerTabOverlay (1.8.9 and
    current are the same): columns of at most 20 rows, column width min(o*(9+i+r+13), b-50)/o, gap 5,
    list from y 10, header/footer wrapped at width - 50 and centred, area 0x80000000, entries
    8 px on a 9 px grid with 0x20FFFFFF."""
    Wd, Hd = screen
    sy = 3.5 if modern else 3
    hdr = wrap_lines(header, Wd - 50)
    ftr = wrap_lines(footer, Wd - 50)
    if not modern:  # String.split("\n") drops trailing empty lines
        while hdr and not hdr[-1]:
            hdr.pop()
        while ftr and not ftr[-1]:
            ftr.pop()
    n = len(players)
    rows, cols = n, 1
    while rows > 20:
        cols += 1
        rows = (n + cols - 1) // cols
    i = max(F.width(p["name"]) for p in players)
    r = max((F.width([(" ", WHITE)] + p["score"]) for p in players if p.get("score")), default=0) if objective else 0
    q = min(cols * (9 + i + r + 13), Wd - 50) // cols
    t = Wd // 2 - (q * cols + (cols - 1) * 5) // 2
    v = q * cols + (cols - 1) * 5
    for line in hdr + ftr:
        v = max(v, F.width(line))
    L, R = Wd // 2 - v // 2 - 1, Wd // 2 + v // 2 + 1
    u = 10
    bg, tx = "", ""
    if hdr:
        bg += rect(L, u - 1, R - L, len(hdr) * 9 + 1, BLACK, 0.5)
        for line in hdr:
            s, _ = F.text_svg(line, Wd // 2 - F.width(line) // 2, u, strike_y=sy)
            tx += s
            u += 9
        u += 1
    bg += rect(L, u - 1, R - L, rows * 9 + 1, BLACK, 0.5)
    for k, p in enumerate(players):
        col, row = divmod(k, rows)
        aa = t + col * q + col * 5
        ab = u + row * 9
        bg += rect(aa, ab, q, 8, WHITE, 0.125)
        bg += head(aa, ab, p.get("head", k))
        x = aa + 9
        s, _ = F.text_svg(p["name"], x, ab, strike_y=sy)
        tx += s
        if objective and p.get("score"):
            right = x + i + 1 + r
            s, _ = F.text_svg(p["score"], right - F.width(p["score"]), ab, strike_y=sy)
            tx += s
        bg += ping_icon(aa + q - 11, ab, bars_for(p.get("ping", 20)))
    u += rows * 9 + 1
    if ftr:
        bg += rect(L, u - 1, R - L, len(ftr) * 9 + 1, BLACK, 0.5)
        for line in ftr:
            s, _ = F.text_svg(line, Wd // 2 - F.width(line) // 2, u, strike_y=sy)
            tx += s
            u += 9
    x0 = max(0, L - crop_margin)
    w = min(Wd, R + crop_margin) - x0
    h = min(Hd, u + 14)
    body = (world_bg(Wd, Hd) if world else rect(0, 0, Wd, Hd, "#3A3A3A")) + bg + tx
    return svg(w, h, body, label, x0, 0), dict(panel=(L, R), bottom=u, cols=cols, rows=rows, col_w=q)


# ---------------------------------------------------------------- Bedrock target design (planned)

BR = MM.BEDROCK


def bedrock_tab(header, footer, players, label="Bedrock-Tab-Liste, Zielbild"):
    """Target design for a custom Bedrock player list (resource pack, JSON UI). Styled after the Bedrock
    interface: grey areas with a dark border and light top edge, rows 14 px, two columns."""
    colw, gap, rowh = 150, 6, 14
    n = len(players)
    rows = (n + 1) // 2
    W = 2 * colw + gap + 24
    inner = W - 24
    lines_h = len(header) * 10
    H = 12 + lines_h + 8 + rows * rowh + 8 + len(footer) * 10 + 12
    o = rect(0, 0, W, H, "#1E1E1F") + rect(2, 2, W - 4, H - 4, "#313233") + rect(2, 2, W - 4, 2, "#5A5B5C")
    y = 12
    tx = ""
    for line in header:
        s, _ = F.text_svg(line, W // 2 - F.width(line) // 2, y, strike_y=3.5)
        tx += s
        y += 10
    y += 4
    o += rect(12, y, inner, rows * rowh + 4, "#1E1E1F")
    y += 2
    for k, p in enumerate(players):
        col, row = divmod(k, rows)
        x = 12 + 2 + col * (colw + gap - 2)
        yy = y + row * rowh
        o += rect(x, yy, colw - 4, rowh - 2, "#48494A") + rect(x, yy, colw - 4, 1, "#6D6D6E")
        o += head(x + 2, yy + 2, p.get("head", k))
        s, _ = F.text_svg(p["name"], x + 13, yy + 3)
        tx += s
        o += ping_icon(x + colw - 18, yy + 3, bars_for(p.get("ping", 20)))
    y += rows * rowh + 10
    for line in footer:
        s, _ = F.text_svg(line, W // 2 - F.width(line) // 2, y, strike_y=3.5)
        tx += s
        y += 10
    return svg(W, H, o + tx, label)
