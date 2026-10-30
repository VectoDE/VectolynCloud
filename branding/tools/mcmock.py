"""Pixel-exact reproductions of the game UI in GUI pixels (scale 1). Displayed only at whole-number
multiples. No Mojang files: font (mcfont), heads and ping icon are our own drawings."""
import mcfont as F

GRAY_D, GRAY, WHITE = "#555555", "#AAAAAA", "#FFFFFF"


def _svg(w, h, body, label):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'shape-rendering="crispEdges" role="img" aria-label="{label}"><title>{label}</title>{body}</svg>')


def rect(x, y, w, h, fill, op=None):
    o = f' fill-opacity="{op}"' if op is not None else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}"{o}/>'


def ping_icon(x, y, bars=5):
    """Simplified redraw of the connection icon (10x8)."""
    out = ""
    for i in range(5):
        h = 2 + i * 1.5
        hh = int(round(h))
        col = "#55FF55" if i < bars else "#3F3F3F"
        out += rect(x + i * 2, y + 8 - hh, 1, hh, col)
    return out


def icon32(mark_pixel_fn, colors, bg):
    """Server icon as displayed: 64x64 icon scaled down to 32x32 = grid cell of 3 px."""
    return rect(0, 0, 32, 32, bg) + mark_pixel_fn(colors, 4, 4, 24)


def to_legacy(segs, nearest):
    return [(s[0], nearest(s[1])[2]) + tuple(s[2:]) for s in segs]


def pad_line(segs, area=270):
    w = F.width(segs)
    n = max(0, round((area - w) / 2 / 4))
    return [(" " * n, WHITE)] + list(segs), n


def server_list(motd, icon, name="Vectolyn", count=("128", "500")):
    """Java server list, one entry (305 x 36), dimensions from the 1.8.9 client."""
    W, H = 305, 36
    body = rect(0, 0, W, H, "#1E1E1E") + icon
    t, _ = F.text_svg([(name, WHITE)], 35, 1)
    body += t
    for i, line in enumerate(motd[:2]):
        assert F.width(line) <= 271, "MOTD line wider than 271 px, the game would wrap it"
        t, _ = F.text_svg(line, 35, 12 + 9 * i)
        body += t
    status = [(f"{count[0]}/{count[1]}", "#808080")]
    sw = F.width(status)
    t, _ = F.text_svg(status, W - sw - 15 - 2, 1)
    body += t + ping_icon(W - 15 + 2, 0)
    return _svg(W, H, body, "Minecraft-Serverliste, Eintrag")


# Bedrock colour values (minecraft.wiki, Formatting codes); differences from Java and extra codes
BEDROCK = {"0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA", "4": "#AA0000", "5": "#AA00AA",
           "6": "#FFAA00", "7": "#C5C5C5", "8": "#545454", "9": "#447FFF", "a": "#54FF54", "b": "#54FFFF",
           "c": "#FF5454", "d": "#FF54FF", "e": "#FFFF54", "f": "#FFFFFF", "g": "#EFCE16", "h": "#D9CCB8",
           "i": "#A9B4B7", "j": "#8F727D", "m": "#EE222C", "n": "#C87363", "p": "#FFBF1E", "q": "#13A045",
           "s": "#5FECFF", "t": "#577BFF", "u": "#B66CDD", "v": "#FF6A00"}  # minecraft.wiki, checked 2026-10-30
JAVA = {"0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA", "4": "#AA0000", "5": "#AA00AA",
        "6": "#FFAA00", "7": "#AAAAAA", "8": "#555555", "9": "#5555FF", "a": "#55FF55", "b": "#55FFFF",
        "c": "#FF5555", "d": "#FF55FF", "e": "#FFFF55", "f": "#FFFFFF"}


def _hsv(x):
    import colorsys
    x = x.lstrip("#")
    return colorsys.rgb_to_hsv(*(int(x[i:i + 2], 16) / 255 for i in (0, 2, 4)))


def geyser_code(hexcol):
    """Geyser: hex -> nearest code via 3*dh^2 + ds^2 + dv^2, candidates §0-§f plus the Bedrock extra codes."""
    h1, s1, v1 = _hsv(hexcol)

    def dist(code):
        h2, s2, v2 = _hsv(BEDROCK[code])
        dh = min(abs(h1 - h2), 1 - abs(h1 - h2))
        return 3 * dh * dh + (s1 - s2) ** 2 + (v1 - v2) ** 2
    return min(BEDROCK, key=dist)


def java_code(hexcol):
    for k, v in JAVA.items():
        if v.lower() == hexcol.lower():
            return k
    return None


def bedrock_segs(segs, via_geyser_hex=False):
    """§ code per segment -> Bedrock colour value. Segments with a Java § colour keep their code."""
    out = []
    for s in segs:
        code = java_code(s[1])
        if code is None:
            code = geyser_code(s[1])
        out.append((s[0], BEDROCK[code]) + tuple(s[2:]))
    return out


def bedrock_entry(motd1, motd2, count=("128", "500")):
    """Bedrock through Geyser: motd1 as the name, motd2 as the sub-line. Interface approximated."""
    W, H = 300, 34
    body = rect(0, 0, W, H, "#313131") + rect(0, 0, W, 1, "#5A5A5A") + rect(0, H - 2, W, 2, "#1B1B1B")
    t, _ = F.text_svg(motd1, 8, 6)
    body += t
    t, _ = F.text_svg(motd2, 8, 18)
    body += t
    status = [(f"{count[0]}/{count[1]}", GRAY)]
    t, _ = F.text_svg(status, W - F.width(status) - 8, 6)
    return _svg(W, H, body + t, "Bedrock server list, entry (approximated)")


HEAD_COLORS = [("#5B3A1E", "#C99A70"), ("#E0A04A", "#F0C090"), ("#2B2B2B", "#8E5B3E"), ("#7A4A2A", "#D7A17A"),
               ("#C8C8C8", "#E8B896"), ("#3B2A1A", "#B07850")]


def head(x, y, i):
    hair, skin = HEAD_COLORS[i % len(HEAD_COLORS)]
    out = rect(x, y, 8, 8, skin) + rect(x, y, 8, 2, hair) + rect(x, y + 2, 1, 1, hair) + rect(x + 7, y + 2, 1, 1, hair)
    out += rect(x + 1, y + 4, 2, 1, "#FFFFFF") + rect(x + 5, y + 4, 2, 1, "#FFFFFF")
    out += rect(x + 2, y + 4, 1, 1, "#3050A0") + rect(x + 5, y + 4, 1, 1, "#3050A0")
    out += rect(x + 3, y + 6, 2, 1, "#6A3A2A")
    return out


def tab_list(header, footer, players, screen_w=427, screen_h=None, heads=True):
    """Player list following the client class flow (PlayerTabOverlay): at most 20 rows per column,
    entry 8 px high on a 9 px grid, background black 50 %, entries white 12.5 %."""
    n = len(players)
    m, o = n, 1
    while m > 20:
        o += 1
        m = (n + o - 1) // o
    i = max(F.width([(p, WHITE)]) for p in players)
    q = min(o * ((9 if heads else 0) + i + 13), screen_w - 50) // o
    p = o * q + (o - 1) * 5
    u = p
    for line in header + footer:
        u = max(u, F.width(line))
    cx = screen_w // 2
    s = 10
    body = ""
    texts = ""
    top = s
    if header:
        body += rect(cx - u // 2 - 1, s - 1, u + 2, len(header) * 9 + 1, "#000000", 0.5)
        for line in header:
            t, _ = F.text_svg(line, cx - F.width(line) // 2, s)
            texts += t
            s += 9
        s += 1
    body += rect(cx - u // 2 - 1, s - 1, u + 2, m * 9 + 1, "#000000", 0.5)
    r = cx - p // 2
    for k, name in enumerate(players):
        col, row = divmod(k, m)
        x, y = r + col * (q + 5), s + row * 9
        body += rect(x, y, q, 8, "#FFFFFF", 0.125)
        nx = x
        if heads:
            body += head(x, y, k)
            nx += 9
        t, _ = F.text_svg([(name, WHITE)], nx, y)
        texts += t
        body += ping_icon(x + q - 11, y)
    s += m * 9 + 1
    if footer:
        body += rect(cx - u // 2 - 1, s - 1, u + 2, len(footer) * 9 + 1, "#000000", 0.5)
        for line in footer:
            t, _ = F.text_svg(line, cx - F.width(line) // 2, s)
            texts += t
            s += 9
    H = screen_h or s + 10
    world = rect(0, 0, screen_w, H, "#5C6B4F")
    return _svg(screen_w, H, world + body + texts, "Minecraft-Spielerliste (Tab)"), (cx - u // 2 - 1, top - 1, u + 2)


def wrap(segs, maxw):
    """Line break at spaces at maxw pixels, colour segments are preserved."""
    words = []
    for s in segs:
        parts = s[0].split(" ")
        for j, part in enumerate(parts):
            if part:
                words.append([(part, s[1]) + tuple(s[2:])])
            if j < len(parts) - 1:
                words.append(None)
    lines, cur = [], []
    for w in words:
        if w is None:
            if cur:
                cur.append((" ", WHITE))
            continue
        trial = cur + w
        if cur and F.width(trial) > maxw:
            while cur and cur[-1][0] == " ":
                cur.pop()
            lines.append(cur)
            cur = list(w)
        else:
            cur = trial
    if cur:
        lines.append(cur)
    return lines


def bedrock_chat(lines_segs, screen_w=360):
    """Bedrock chat, approximated: own interface, text without a click target."""
    lines = []
    for segs in lines_segs:
        lines += wrap(segs, 320)
    H = len(lines) * 10 + 8
    body = rect(0, 0, screen_w, H, "#5C6B4F") + rect(0, 4, 330, len(lines) * 10, "#000000", 0.6)
    texts = ""
    for i, line in enumerate(lines):
        t, _ = F.text_svg(line, 4, 5 + i * 10)
        texts += t
    return _svg(screen_w, H, body + texts, "Bedrock chat (approximated)")


def chat(lines_segs, chat_w=320, screen_w=360):
    """Chat window: width 320, lines 9 px, background black 50 %, text from x 4 (background from x 0)."""
    lines = []
    for segs in lines_segs:
        lines += wrap(segs, chat_w)
    H = len(lines) * 9 + 8
    body = rect(0, 0, screen_w, H, "#5C6B4F")
    y = 4
    texts = ""
    for line in lines:
        body += rect(0, y, chat_w + 12, 9, "#000000", 0.5)
        t, _ = F.text_svg(line, 4, y + 1)
        texts += t
        y += 9
    return _svg(screen_w, H, body + texts, "Minecraft-Chat")
