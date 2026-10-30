"""In-game content, version 3: MOTD, neighbouring entries, tab list (TAB configuration from the repo and
Vectolyn branding), chat, console."""
import colorsys

import brand3 as B
import mcfont as F
import mcmock as MM
import mcui as U

CY, GO = B.C["cyan"], B.C["gold"]
WHITE, GRAY, GRAY_D, YELLOW = "#FFFFFF", "#AAAAAA", "#555555", "#FFFF55"
DAQUA, DGREEN, GOLD6, AQUA, GREEN = "#00AAAA", "#00AA00", "#FFAA00", "#55FFFF", "#55FF55"
JAVA = MM.JAVA


def legacy_nearest(h):
    """Nearest of the 16 § colours by RGB distance (roughly how TAB and Velocity treat old clients)."""
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    best = min(JAVA.items(), key=lambda kv: sum((a - int(kv[1][i:i + 2], 16)) ** 2 for a, i in zip((r, g, b), (1, 3, 5))))
    return best


def adv_nearest(h):
    """Adventure NamedTextColor.nearestTo: HSV distance, hue weighted threefold (Velocity for clients < 1.16)."""
    def hsv(x):
        return colorsys.rgb_to_hsv(*(int(x[i:i + 2], 16) / 255 for i in (1, 3, 5)))
    h1, s1, v1 = hsv(h)

    def dist(kv):
        h2, s2, v2 = hsv(kv[1])
        hd = 3 * min(abs(h1 - h2), 1 - abs(h1 - h2))
        return hd * hd + (s1 - s2) ** 2 + (v1 - v2) ** 2
    for kv in JAVA.items():
        if kv[1].lower() == h.lower():
            return kv
    return min(JAVA.items(), key=dist)


def to_legacy(segs, fn=legacy_nearest):
    return [(s[0], fn(s[1])[1]) + tuple(s[2:]) for s in segs]


def gradient(n, a, b, strike=True):
    ra, rb = [int(a[i:i + 2], 16) for i in (1, 3, 5)], [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    out = []
    for k in range(n):
        t = k / (n - 1)
        col = "#" + "".join(f"{round(ra[i] + (rb[i] - ra[i]) * t):02X}" for i in range(3))
        out.append((" ", col, 0, 1 if strike else 0))
    return out


# ---------------------------------------------------------------- MOTD
MOTD = [
    [("VECTOLYN", CY, 1), (".NET", GO, 1), (" ■ ", GO, 0), ("[", GRAY_D, 1), ("1.8 - 26.3", WHITE, 1), ("]", GRAY_D, 1)],
    [("NEW LOBBY", WHITE, 1), (" • ", GRAY_D), ("JAVA & BEDROCK", GO, 1)],
]
# Java 1.8: MOTD planned for our own proxy plugin (stage 3); until then Velocity maps cyan to §3 via Adventure
MOTD18 = [
    [("VECTOLYN", AQUA, 1), (".NET", GOLD6, 1), (" ■ ", GOLD6, 0), ("[", GRAY_D, 1), ("1.8 - 26.3", WHITE, 1), ("]", GRAY_D, 1)],
    [("NEW LOBBY", WHITE, 1), (" • ", GRAY_D), ("JAVA & BEDROCK", GOLD6, 1)],
]
# Legacy proxy (templates/Legacy-Proxy): line 1 carries the network-wide version range like the main proxy, line 2
# names the legacy section. Its backends are PandaSpigot 1.8.8 (protocol 47) without protocol translation, so its
# clients are 1.8 clients: the 1.8 palette (aqua §b, gold §6) directly, since none of them shows hex colours.
MOTD_LEGACY_PROXY = [
    [("VECTOLYN", AQUA, 1), (".NET", GOLD6, 1), (" ■ ", GOLD6, 0), ("[", GRAY_D, 1), ("1.8 - 26.3", WHITE, 1), ("]", GRAY_D, 1)],
    [("LEGACY 1.8", WHITE, 1), (" • ", GRAY_D), ("JAVA & BEDROCK", GOLD6, 1)],
]
# Bedrock: no Java version range, crossplay as text instead (Geyser's Bedrock range keeps changing).
MOTD_BEDROCK =[("VECTOLYN", CY, 1), (".NET", GO, 1), (" ■ ", GO, 0), ("[", GRAY_D, 1), ("JAVA + BEDROCK", WHITE, 1), ("]", GRAY_D, 1)]
COUNT = ("128", "500")

NEIGHBOURS = [
    dict(name="Example Network A", count=("2140", "5000"), icon=U.placeholder_icon("A", "#B83A3A", "#5A1414", "#FFE0E0"),
         motd=[[("EXAMPLE-A", "#FF5555", 1), (".NET", WHITE, 1), ("  1.20 - 26.3", GRAY)],
               [("SKYBLOCK", "#55FF55", 1), (" + ", GRAY_D), ("BEDWARS", "#FFAA00", 1), (" + ", GRAY_D),
                ("CITYBUILD", "#55FFFF", 1)]]),
    dict(name="Example Network B", count=("312", "1000"), icon=U.placeholder_icon("B", "#7A3AB8", "#2E1450", "#F3E0FF"),
         motd=[[("» ", GRAY_D), ("EXAMPLE-B.COM", "#FF55FF", 1), (" «", GRAY_D)],
               [("SURVIVAL", "#FFFF55", 1), (" SEASON 4 ", WHITE, 1), ("NOW LIVE", "#FF5555", 1)]]),
    None,  # Vectolyn
    dict(name="Example Network C", count=("87", "200"), icon=U.placeholder_icon("C", "#3A8A4A", "#123A1A", "#E0FFE6"),
         motd=[[("EXAMPLE-C", "#55FF55", 1), (".EU", "#00AA00", 1), (" | ", GRAY_D), ("FARMWORLD", WHITE, 1)],
               [("Maintenance until 6 pm", GRAY)]]),
    dict(name="Example Network D", count=("1", "20"), bars=3, icon=U.placeholder_icon("D", "#B87A2A", "#4A2A0A", "#FFF0D8"),
         motd=[[("Small survival server", "#FFAA00")], [("Whitelist only", GRAY)]]),
]


def entries(modern):
    out = []
    for e in NEIGHBOURS:
        if e is None:
            lines = MOTD if modern else MOTD18
            out.append(dict(name="Vectolyn", count=COUNT, icon=B.server_icon64(),
                            motd=[U.center(l)[0] for l in lines]))
        else:
            m = e["motd"] if modern else [to_legacy(l) for l in e["motd"]]
            out.append(dict(e, motd=[U.center(l)[0] for l in m]))
    return out


# ---------------------------------------------------------------- Tab list
# Players, groups as in scoreboard-teams.sorting-types: GROUPS:owner,admin,mod,helper,builder,vip,default, then A-Z
ORDER = ["owner", "admin", "mod", "helper", "builder", "vip", "default"]
PEOPLE = [("owner", "Henrik"), ("admin", "Mara_"), ("admin", "Tjark"), ("mod", "Ayla"), ("mod", "Bent"),
          ("mod", "Juna"), ("helper", "Emir"), ("helper", "Liv"), ("builder", "FinnBuilds"), ("builder", "Smilla"),
          ("vip", "Ole_PvP"), ("vip", "Paula"), ("vip", "Ilyas"), ("vip", "Merve"), ("vip", "Hanno"), ("vip", "Kjell"),
          ("default", "Kai"), ("default", "Alex_K"), ("default", "Noor"), ("default", "xTobi"), ("default", "Lea_Voxel"),
          ("default", "Jonas_77"), ("default", "Mats"), ("default", "Ida"), ("default", "Ruben"), ("default", "Selin"),
          ("default", "Theo"), ("default", "Wiebke"), ("default", "Yusuf"), ("default", "Zoe")]
PINGS = [12, 18, 24, 31, 9, 44, 27, 15, 38, 22, 61, 19, 33, 28, 47, 14, 24, 35, 52, 88, 21, 17, 40, 26, 30, 23,
         58, 16, 72, 29]
SORTED = sorted(PEOPLE, key=lambda p: (ORDER.index(p[0]), p[1]))  # case-sensitive: capitals before lower case
VIEWER, ONLINE, STAFF = "Kai", len(PEOPLE), 8

RANKS = {  # Vectolyn branding: prefix, current colour (hex), 1.8 colour (§), Bedrock colour (§)
    "owner": ("Owner", "#AA0000", "#AA0000", MM.BEDROCK["4"]),
    "admin": ("Admin", "#FF5555", "#FF5555", MM.BEDROCK["c"]),
    "mod": ("Mod", CY, AQUA, MM.BEDROCK["b"]),
    "helper": ("Helper", "#55FF55", "#55FF55", MM.BEDROCK["a"]),
    "builder": ("Builder", "#5555FF", "#5555FF", MM.BEDROCK["9"]),
    "vip": ("VIP", GO, GOLD6, MM.BEDROCK["p"]),
    "default": (None, GRAY, GRAY, MM.BEDROCK["7"]),
}


def players_tab(kind):
    """kind: tab (TAB as in the repo: empty LuckPerms prefixes, names white), brand, brand18, bedrock."""
    out = []
    for k, (grp, name) in enumerate(SORTED):
        ping = PINGS[PEOPLE.index((grp, name))]
        if kind == "tab":
            segs = [(name, WHITE)]
        else:
            label, hexc, leg, bed = RANKS[grp]
            col = {"brand": hexc, "brand18": leg, "bedrock": bed}[kind]
            sep = MM.BEDROCK["8"] if kind == "bedrock" else GRAY_D
            segs = [(label, col), (" | ", sep), (name, col)] if label else [(name, col)]
        out.append(dict(name=segs, ping=ping, head=k * 3 + 1))
    return out


def tab_original(modern):
    """TAB design "default" from templates/*/plugins/TAB/config.yml with example values.
    Animations: Welcome frame 8 ("Welcome_"), time frame 1 (clock), web frame 1 (static)."""
    sep = gradient(48, "#FFFFFF", "#FFFF00")
    head = [sep,
            [("Server name", DAQUA, 1)],
            [(">> ", GRAY, 1), ("Welcome_", GRAY, 1), (" ", DAQUA), (VIEWER, DAQUA, 1), ("! ", GRAY, 1), ("<<", GRAY, 1)],
            [("Online players: ", GRAY), (str(ONLINE), WHITE)],
            [("Online staff: ", GOLD6), (str(STAFF), YELLOW)],
            []]
    foot = [[("Time ", GRAY), ("[14:32:05 / 2:32 PM]", AQUA)],
            [("Ping: 24", DGREEN)],
            [(" Used memory: 1312 MB / 4096 MB", GRAY, 1)],
            [],
            [("Visit our webpage ", GRAY), ("www.domain.com", YELLOW)],
            sep]
    pl = players_tab("tab")
    for p in pl:
        p["score"] = [("Ping: " + str(p["ping"]), GRAY)] if modern else [(str(p["ping"]), YELLOW)]
    if not modern:
        head = [to_legacy(l) for l in head]
        foot = [to_legacy(l) for l in foot]
    return U.tab_list(head, foot, pl, modern, objective=True,
                      label="Tab-Liste wie im Repo (TAB), " + ("Java aktuell" if modern else "Java 1.8"))


def brand_lines(modern, frame="time"):
    """Vectolyn branding: structure of the TAB design, header with VECTOLYN.NET, footer complete as in the original
    plus Discord, TeamSpeak and the powered-by line. frame: time or date (animation time, both frames)."""
    if modern:
        sep = gradient(48, CY, GO)
        cy, go = CY, GO
        logo = [("", WHITE)]
    else:
        sep = [(" " * 48, GRAY_D, 0, 1)]
        cy, go = AQUA, GOLD6
        logo = []
    head = [sep,
            (logo + [("ECTOLYN", cy, 1), (".NET", go, 1)]) if logo else [("VECTOLYN", cy, 1), (".NET", go, 1)],
            *([[("", WHITE)]] if logo else []),  # blank line: the glyph's gold descender reaches 2 px below the line
            [("» ", GRAY_D, 1), ("Welcome, ", GRAY), (VIEWER, cy, 1), ("!", GRAY), (" «", GRAY_D, 1)],
            [("Online players: ", GRAY), (str(ONLINE), WHITE)],
            [("Online staff: ", GRAY), (str(STAFF), go)],
            []]
    tl = [("Time ", GRAY), ("14:32", cy)] if frame == "time" else [("Date ", GRAY), ("2026-10-30", cy)]
    foot = [tl,
            [("Ping: ", GRAY), ("24 ms", GREEN)],
            [("Used memory: ", GRAY), ("1312 MB", WHITE), (" / ", GRAY_D), ("4096 MB", WHITE)],
            [],
            [("Discord: ", GRAY), ("discord.gg/vectolyn", WHITE)],
            [("TeamSpeak: ", GRAY), ("ts.vectolyn.net", WHITE)],
            [("Website: ", GRAY), ("vectolyn.net", go)],
            sep,
            [("Powered by VectolynCloud", GRAY_D)]]
    return head, foot


def tab_brand(modern, frame="time"):
    head, foot = brand_lines(modern, frame)
    return U.tab_list(head, foot, players_tab("brand" if modern else "brand18"), modern, objective=False,
                      label="Tab-Liste Vectolyn-Branding, " + ("Java aktuell" if modern else "Java 1.8"))


def tab_bedrock():
    b = MM.BEDROCK
    head = [[("", WHITE), ("ECTOLYN", b["b"], 1), (".NET", b["p"], 1)],
            [("", b["f"])],
            [("» ", b["8"], 1), ("Welcome, ", b["7"]), (VIEWER, b["b"], 1), ("!", b["7"]), (" «", b["8"], 1)],
            [("Online players: ", b["7"]), (str(ONLINE), b["f"]), ("  |  ", b["8"]), ("Online staff: ", b["7"]),
             (str(STAFF), b["p"])]]
    foot = [[("Time ", b["7"]), ("14:32", b["b"]), ("  |  ", b["8"]), ("Ping: ", b["7"]), ("24 ms", b["a"]),
             ("  |  ", b["8"]), ("Used memory: ", b["7"]), ("1312 / 4096 MB", b["f"])],
            [("Discord: ", b["7"]), ("discord.gg/vectolyn", b["f"]), ("  |  ", b["8"]), ("TeamSpeak: ", b["7"]),
             ("ts.vectolyn.net", b["f"])],
            [("Website: ", b["7"]), ("vectolyn.net", b["p"])],
            [("Powered by VectolynCloud", b["8"])]]
    return U.bedrock_tab(head, foot, players_tab("bedrock"))


# ---------------------------------------------------------------- Chat /version and powered-by line
URL = B.URL
POWERED = {
    "tab_en": [("Powered by VectolynCloud", GRAY_D)],
    "tab_de": [("Läuft mit VectolynCloud", GRAY_D)],
    "version_de": [[("Dieses Netzwerk läuft mit ", GRAY), ("VectolynCloud 0.2.0", CY)],
                   [("Open Source (GPL-3.0): ", GRAY), (URL, WHITE)]],
    "version_en": [[("This network runs ", GRAY), ("VectolynCloud 0.2.0", CY)],
                   [("Open source (GPL-3.0): ", GRAY), (URL, WHITE)]],
}


# ---------------------------------------------------------------- Console (format of StartupReport)
FIG = [
    r"__     __        _        _              ____ _                 _ ",
    r"\ \   / /__  ___| |_ ___ | |_   _ _ __  / ___| | ___  _   _  __| |",
    r" \ \ / / _ \/ __| __/ _ \| | | | | '_ \| |   | |/ _ \| | | |/ _` |",
    r"  \ V /  __/ (__| || (_) | | |_| | | | | |___| | (_) | |_| | (_| |",
    r"   \_/ \___|\___|\__\___/|_|\__, |_| |_|\____|_|\___/ \__,_|\__,_|",
    r"                            |___/                                 ",
]
FIG_NARROW = [
    r"__   __      _       _           ___ _             _ ",
    r"\ \ / /__ __| |_ ___| |_  _ _ _ / __| |___ _  _ __| |",
    r" \ V / -_) _|  _/ _ \ | || | ' \ (__| / _ \ || / _` |",
    r"  \_/\___\__|\__\___/_|\_, |_||_\___|_\___/\_,_\__,_|",
    r"                       |__/                          ",
]
INFO = "VectolynCloud 0.2.0  |  Java 21.0.5 (Eclipse Adoptium)  |  Windows 11 amd64"
HOME = r"C:\vectolyn"


def step(n, title, status, detail):
    return f"[{n}/5] {title:<14} {status:<8} {detail}"


def svc(sid, state, port, mode):
    return f"      {sid:<14} {state:<8} port {port}, {mode}"


BOOT = [("10:15:02", "INFO ", step(1, "Configuration", "ok", "9 groups, 9 tasks, node node-1")),
        ("10:15:03", "INFO ", step(2, "Database", "ok", "mariadb on 127.0.0.1:3307")),
        ("10:15:05", "INFO ", step(3, "Software", "ok", "lobby: paper 26.3 + 6 plugins; proxy: velocity 26.3 + 5 plugins")),
        ("10:15:05", "INFO ", step(4, "Templates", "ok", "2 written (Lobby-Server, Proxy), 0 present, for 2 wanted server(s)")),
        ("10:15:06", "INFO ", step(5, "Services", "ok", "2 of 2 started")),
        ("10:15:06", "INFO ", svc("lobby-1", "starting", 30001, "static")),
        ("10:15:06", "INFO ", svc("proxy-1", "starting", 25565, "static"))]


def banner_lines(narrow=False):
    art = FIG_NARROW if narrow else FIG
    return [""] + ["  " + a.rstrip() for a in art] + ["", "  " + INFO, "  Home: " + HOME, ""]


def console_text():
    lines = banner_lines() + [f"{t} {lvl} {msg}" for t, lvl, msg in BOOT]
    return "\n".join(lines) + "\n"


def console_banner_txt():
    wide = banner_lines()
    narrow = banner_lines(True)
    for l in wide + narrow:
        assert all(ord(ch) < 128 for ch in l) and len(l) <= 80, l
    return ("# VectolynCloud console banner, plain ASCII. Wide: up to 68 columns (fits 80). Narrow: up to 55 columns,\n"
            "# for consoles narrower than 70 columns. Printed like StartupReport.banner(): blank line, lettering,\n"
            "# blank line, info line, home, blank line. Steps [1/5] to [5/5] follow through the log.\n"
            "# --- wide ---\n" + "\n".join(wide) + "\n# --- narrow ---\n" + "\n".join(narrow) + "\n")


# ---------------------------------------------------------------- Configuration texts

def mm(segs):
    names = {v.lower(): k for k, v in [("black", "#000000"), ("dark_blue", "#0000AA"), ("dark_green", "#00AA00"),
             ("dark_aqua", "#00AAAA"), ("dark_red", "#AA0000"), ("dark_purple", "#AA00AA"), ("gold", "#FFAA00"),
             ("gray", "#AAAAAA"), ("dark_gray", "#555555"), ("blue", "#5555FF"), ("green", "#55FF55"),
             ("aqua", "#55FFFF"), ("red", "#FF5555"), ("light_purple", "#FF55FF"), ("yellow", "#FFFF55"),
             ("white", "#FFFFFF")]}
    out = ""
    for s in segs:
        nm = names.get(s[1].lower())
        t = s[0].replace("<", "\\<")
        out += (f"<{nm}>" if nm else f"<{s[1]}>") + (f"<bold>{t}</bold>" if len(s) > 2 and s[2] else t)
    return out


def legacy_codes(segs):
    out = ""
    for s in segs:
        code = [k for k, v in JAVA.items() if v.lower() == s[1].lower()]
        code = code[0] if code else legacy_nearest(s[1])[0]
        out += "§" + code + ("§l" if len(s) > 2 and s[2] else "") + s[0]
    return out


def bedrock_codes(segs, table):
    return "".join("§" + table[s[1]] + ("§l" if len(s) > 2 and s[2] else "") + s[0] for s in segs)


def motd_txt():
    cen = [U.center(l) for l in MOTD]
    cen18 = [U.center(l) for l in MOTD18]
    cenlp = [U.center(l) for l in MOTD_LEGACY_PROXY]
    mmlp = "\\n".join(" " * n + mm(l) for (_, n, _w), l in zip(cenlp, MOTD_LEGACY_PROXY))
    mml = "\\n".join(" " * n + mm(l) for (_, n, _w), l in zip(cen, MOTD))
    bt = {CY: "b", GO: "p", GRAY_D: "8", WHITE: "f"}
    return (
        "# Velocity (templates/Proxy/velocity.toml), MiniMessage, two centred lines.\n"
        "# Centred with the font's advance table: area 270 px, space 4 px, bold +1 px per glyph.\n"
        f"# Line 1: {cen[0][2]} px -> {cen[0][1]} spaces; line 2: {cen[1][2]} px -> {cen[1][1]} spaces.\n"
        f'motd = "{mml}"\n\n'
        "# Java 1.8 to 1.15 (no hex colours): planned as a separate MOTD from our own proxy plugin (stage 3),\n"
        "# § codes, same centring. Until then these clients get the MOTD above, which Velocity downsamples\n"
        "# via Adventure: cyan becomes §3 (dark aqua).\n"
        f'motd-legacy-1: "{" " * cen18[0][1]}{legacy_codes(MOTD18[0])}"\n'
        f'motd-legacy-2: "{" " * cen18[1][1]}{legacy_codes(MOTD18[1])}"\n\n'
        "# Legacy proxy (templates/Legacy-Proxy/velocity.toml), MiniMessage, same centring. Line 1 shows the network-wide\n"
        "# range like the main proxy; its clients are 1.8 clients (PandaSpigot 1.8.8 backends), so named 1.8 colours.\n"
        f"# Line 1: {cenlp[0][2]} px -> {cenlp[0][1]} spaces; line 2: {cenlp[1][2]} px -> {cenlp[1][1]} spaces.\n"
        f'motd = "{mmlp}"\n\n'
        "# Geyser (plugins/Geyser-Velocity/config.yml), Bedrock § codes, not centred.\n"
        "# Bedrock shows motd1 in the server list; per a Geyser source comment the sub-MOTD only shows locally.\n"
        f'primary-motd: "{bedrock_codes(MOTD_BEDROCK, bt)}"\n'
        f'secondary-motd: "{bedrock_codes(MOTD[1], bt)}"\n')


def tab_yaml():
    """Vectolyn branding as a TAB design (interim, until our own plugin sets the list)."""
    sep = "<gradient:#00E5FF:#FFD23F><st>" + " " * 48 + "</st></gradient>"
    welcome = ["W", "We", "Wel", "Welc", "Welco", "Welcom", "Welcome", "Welcome,"] + ["Welcome,"] * 6
    L = [
        "# TAB design with Vectolyn branding (MiniMessage, components.minimessage-support: true).",
        "# Addresses are examples. \\uE000 is the mark glyph (U+E000) and replaces the V of VECTOLYN; use it only once",
        "# the resource pack provides it. Without the pack, write the name in full (VECTOLYN.NET). Never \\uE000 followed by VECTOLYN.",
        "# The last footer line is the powered-by line; it follows branding.powered-by (default true) in cloud.yml.",
        "# config.yml",
        "header-footer:",
        "  designs:",
        "    vectolyn:",
        "      header:",
        f'        - "{sep}"',
        '        - "<white>\\uE000<#00E5FF><bold>ECTOLYN</bold><#FFD23F><bold>.NET</bold>"',
        '        - ""',
        '        - "<dark_gray><bold>» </bold><gray>%animation:VectolynWelcome% <#00E5FF><bold>%player%</bold>'
        '<gray>!<dark_gray><bold> «</bold>"',
        '        - "<gray>Online players: <white>%online%"',
        '        - "<gray>Online staff: <#FFD23F>%staffonline%"',
        '        - ""',
        "      footer:",
        '        - "%animation:VectolynTime%"',
        '        - "<gray>Ping: <green>%ping% ms"',
        '        - "<gray>Used memory: <white>%memory-used% MB<dark_gray> / <white>%memory-max% MB"',
        '        - ""',
        '        - "<gray>Discord: <white>discord.gg/vectolyn"',
        '        - "<gray>TeamSpeak: <white>ts.vectolyn.net"',
        '        - "<gray>Website: <#FFD23F>vectolyn.net"',
        f'        - "{sep}"',
        '        - "<dark_gray>Powered by VectolynCloud"',
        "placeholders:",
        '  time-format: "HH:mm"',
        '  date-format: "yyyy-MM-dd"',
        "",
        "# animations.yml",
        "VectolynWelcome:",
        "  change-interval: 400",
        "  texts:",
    ] + [f'    - "{w}"' for w in welcome] + [
        "VectolynTime:",
        "  change-interval: 3000",
        "  texts:",
        '    - "<gray>Time <#00E5FF>%time%"',
        '    - "<gray>Date <#00E5FF>%date%"',
    ]
    return "\n".join(L) + "\n"
