"""Brand guide page (English), version 3. Editorial layout: section rail left, content right, full-bleed media."""
import html
import re

import brand3 as B
import core as ga

E = html.escape
C = B.C


def dims(svgtxt):
    m = re.search(r'viewBox="[\d.\-]+ [\d.\-]+ ([\d.]+) ([\d.]+)"', svgtxt)
    return float(m.group(1)), float(m.group(2))


def inline(svgtxt, w=None, h=None, cls="", label=None):
    s = re.sub(r' width="[\d.]+" height="[\d.]+"', "", svgtxt, count=1)
    a = (f' width="{w}"' if w else "") + (f' height="{h}"' if h else "") + (f' class="{cls}"' if cls else "")
    s = s.replace("<svg ", f"<svg{a} ", 1)
    return s


def img(src, svgtxt, k, alt, cls="px"):
    w, h = dims(svgtxt)
    return f'<img src="{src}" width="{int(w * k)}" height="{int(h * k)}" alt="{E(alt)}" class="{cls}" loading="lazy" decoding="async">'


def facts(exact, approx):
    li = lambda xs: "".join(f"<li>{E(x)}</li>" for x in xs)
    return (f'<div class="facts"><div><h4 class="ok">Exact</h4><ul>{li(exact)}</ul></div>'
            f'<div><h4 class="ap">Approximated or unverified</h4><ul>{li(approx)}</ul></div></div>')


GLYPHS = ("Glyph shapes are our own redraw of the game font (no Mojang file). Advance widths follow the font's "
          "advance table, so widths and centering are exact even where a glyph's look is approximate.")
SYMBOLS = "» « • ■: advances 7, 7, 3 and 5 as drawn; the shape of all four and the advance of ■ are not verified in a client."
PING = "Ping icon: own 5-bar redraw. A screenshot of a current Java client shows a wedge-shaped icon instead; not yet checked against a client without resource packs."
MODERN = ("Modern clients: layout numbers taken as equal to 1.8.9 (decompiled), cross-checked against a client "
          "screenshot at GUI scale 3 (entry 915 px wide = 305 x 3). Not checked in the 26.x source.")

F_SERVER = (["Entry 305 x 36 on a 36 px pitch; icon 32 x 32 (the 64 x 64 PNG at half size, so at GUI scale 2 it shows 1:1)",
             "Name at x+35, y+1 in white; MOTD lines at x+35, y+12 and y+21; wrap width 271",
             "Player count right-aligned at 305 - width - 17, y+1; ping icon at x+290",
             "Selection: 1 px frame around the entry (white while focused), black fill, content inset 2 px",
             "Bold = glyph drawn twice 1 px apart, +1 px advance; shadow +1/+1 in (rgb & 0xFCFCFC) >> 2",
             "Centering: leading spaces (4 px) counted against a 270 px area"],
            [
             "Text shadow and count colors (white digits, gray slash) measured on a screenshot of a current client",
             "Background: own blurred landscape in place of the title panorama, darkened to match a client screenshot"])
F_SERVER18 = (["Same geometry as above (1.8.9 GuiSlot / ServerListEntryNormal)",
               "No text shadow: 1.8.9 draws the list with drawString(..., false)",
               "Selection frame gray #808080 with black fill",
               "Count as §7 online §8/ §7 max; MOTD colors as § codes (§b, §6)"],
              [
               "Background: own dark tiled noise in place of the dirt texture",
               "This legacy MOTD is planned for our own proxy plugin (stage 3) and not built yet. Until then "
               "Velocity downsamples the modern MOTD via Adventure, so cyan shows as §3 (dark aqua); not tested"])
F_BEDROCK = (["MOTD as § codes in Bedrock color values from minecraft.wiki (§b #54FFFF, §p #FFBF1E, §8 #545454)"],
             ["Row layout, sizes and colors of the Bedrock server list are an approximation; no client was measured",
              "Bedrock shows motd1 below the server name; the sub-MOTD (line 2) only appears locally, per a comment in "
              "Geyser's source. Not tested on a device",
              "Font: same redraw as Java (Bedrock uses Mojangles); coverage of » « • ■ on Bedrock is not verified"])
F_TAB = (["Lines, colors and placeholders from templates/*/plugins/TAB/config.yml (design default) and animations.yml",
          "Sorting from scoreboard-teams: GROUPS owner, admin, mod, helper, builder, vip, default, then A-Z",
          "PlayerTabOverlay: at most 20 rows per column, column width min(o * (9 + name + score + 13), width - 50) / o, "
          "5 px gap, list from y 10, header and footer wrapped at width - 50 and centered",
          "Background 0x80000000; rows 8 px on a 9 px pitch with 0x20FFFFFF; heads 8 x 8; ping icon at x + width - 11",
          "playerlist-objective: 1.20.3+ shows the fancy value (gray Ping: n), older clients a yellow number",
          "Strikethrough: 1 px line at y+3.5 from x-1 (modern) or y+3 (1.8), with shadow"],
         ["Heads: placeholder faces; skins are player data",
          "World: own flat drawing; screen 640 x 360 GUI px (1920 x 1080 at GUI scale 3)",
          "Example values: 30 players, 8 staff, ping 24, memory 1312 / 4096 MB, 14:32:05",
          "Animations: a frame of the typing effect is shown (Welcome_), time and web lines static",
          "LuckPerms prefixes are not set in the repo, so names show plain white (groups only change the order)",
          "1.8: TAB's RGB to § conversion assumed as nearest color; the trailing empty header line is dropped "
          "(String.split) on 1.8 and kept on modern clients (not verified)"])
F_TABV = (["Same layout rules as the TAB list above; same structure of lines",
           "Footer complete: time/date animation (both frames shown), ping, used memory, Discord, TeamSpeak, website, "
           "powered-by line last in dark gray"],
          ["Mark glyph U+E000 replaces the V of VECTOLYN (8 x 10, V body on the 7 px cap height, gold block as a 3 px "
           "descender). Needs our resource pack (bitmap font provider, Java 1.13+); without it, write VECTOLYN in full. "
           "The descender reaches 1 px into the next line; check in game", "Addresses vectolyn.net, discord.gg/vectolyn, ts.vectolyn.net are examples",
           "1.8 variant: hex gradient replaced by a dark gray rule, cyan/gold by §b/§6, no glyph"])
F_BTAB = (["Bedrock color values from minecraft.wiki; Bedrock has no strikethrough, so rules are drawn as UI lines"],
          ["Target design, not current behavior: vanilla Geyser ignores the tab header/footer packet",
           "Planned for stage 3 (own plugin plus Bedrock resource pack JSON UI or a Geyser extension)",
           "Panel look modeled on Bedrock's UI (gray panels, dark border, light top edge); sizes are a proposal"])
F_CHAT = (["Chat width 320, word wrap, 9 px lines, black background at 50 %, text with shadow"],
          ["World: placeholder color"])
F_BCHAT = (["Link is not clickable on Bedrock (Geyser documentation)"],
           ["Chat UI of Bedrock approximated; Geyser's hex-to-code mapping not tested"])

SPELLING = [
    ("Product", "VectolynCloud", "Vectolyn Cloud, Vectolyncloud, Vectolyn-Cloud",
     "One word, two capitals. No article: “VectolynCloud starts the services.”"),
    ("Brand, toolkit", "Vectolyn", "VECTOLYN in running text",
     "“Vectolyn is an open source toolkit”: cloud, plugins, templates, resource pack and website together."),
    ("Network in game", "VECTOLYN.NET", "Vectolyn.Net, vectolyn.NET",
     "Display only: MOTD, scoreboard title, tab list, signs. VECTOLYN.CLOUD where the cloud is the topic. The suffix, dot included, is always gold."),
    ("Address", "vectolyn.net", "VECTOLYN.NET in links", "Lowercase, and only once the domain exists."),
    ("Wordmark", "VECTOLYNCLOUD (artwork, two colors)", "a space between VECTOLYN and CLOUD", "One word; color separates the parts. In text it stays VectolynCloud."),
    ("File", "VectolynCloud.jar", "vectolyncloud.jar, VectolynCloud-0.2.0.jar", "Same pattern as today's jar."),
    ("Technical names", "vectolyn-cloud", "vectolynCloud, vectolyn_cloud",
     "ghcr.io/vectode/vectolyn-cloud, vectolyn-cloud.service, Java packages net.vectolyn.cloud.*, repo github.com/VectoDE/VectolynCloud."),
    ("Console", "VectolynCloud 0.2.0", "Vectolyn Cloud v0.2.0", "First info line: name, space, version without “v”."),
    ("Versions", "1.8 to 26.3", "1.8–26.3 in running text", "In the MOTD “1.8 - 26.3” with an ASCII hyphen; old clients may not render the en dash."),
    ("Editions", "Java and Bedrock", "Java Version, MCPE", "“JAVA & BEDROCK” only in the MOTD, where space is short."),
]

TONE = [
    ("Network to players", ["Short, present tense, second person. Say what is happening and where to go.",
                            "Use the words players use: lobby, mode, round, portal.",
                            "Capitals only in the MOTD and on signs, never whole chat sentences.",
                            "Errors say what happened and how long it takes."],
     [("The new lobby is open. The portal on your right leads to Survival.", True),
      ("Survival is restarting. You will be back in about 30 seconds.", True),
      ("Experience the ultimate next-gen gaming adventure!", False),
      ("Oops! Something went wrong :(", False)]),
    ("Cloud to operators", ["Second person, as in the README. Action first, reason second.",
                            "Commands, paths and ports verbatim, set as code.",
                            "Numbers instead of adjectives: “starts in about 20 seconds”.",
                            "Vectolyn ships code, not hosting. Never write as if we run a network."],
     [("Run java -jar VectolynCloud.jar. On first start the wizard asks for the port, the database and the servers you want.", True),
      ("MariaDB does not answer on 127.0.0.1:3306. Check the service with systemctl status mariadb.", True),
      ("Seamlessly scale your network with powerful cloud orchestration.", False),
      ("An unexpected error occurred.", False)]),
]

UNVERIFIED = [
    "Trademark: “Vectolyn” has not been searched at DPMA, EUIPO or WIPO. Have a lawyer check it before wider public use.",
    "Domains: vectolyn.net, vectolyn.cloud and vectolyn.com returned 404 on RDAP on 2026-10-30, so they were probably "
    "unregistered at that moment. Nothing is registered yet.",
    "In-game views: advances, colors, bold and shadow follow fixed rules; glyph shapes are our redraw. Photograph "
    "Java 26.x, Java 1.8.9 and Bedrock once and compare before release.",
    "Symbols » « • ■ on Java 1.8 and Bedrock: coverage and exact shapes not checked in a client.",
    "Resource-pack glyph U+E000 (the mark): Java bitmap provider from 1.13; Bedrock glyph pages; not built or tested yet.",
    "3D renders: Cycles with Khronos PBR Neutral; colors on screen are close to, not equal to, the flat brand hex values.",
]


CSS = r"""
@font-face{font-family:'Atkinson Hyperlegible Next';src:url(fonts/atkinson-hyperlegible-next/AtkinsonHyperlegibleNext-Variable.ttf) format('truetype');font-weight:200 800;font-style:normal;font-display:swap}
@font-face{font-family:'JetBrains Mono';src:url(fonts/jetbrains-mono/JetBrainsMono-Variable.ttf) format('truetype');font-weight:100 800;font-style:normal;font-display:swap}
@font-face{font-family:'Pixelify Sans';src:url(fonts/pixelify-sans/PixelifySans-Variable.ttf) format('truetype');font-weight:400 700;font-style:normal;font-display:swap}
:root{--bg:#F3F5F7;--bg2:#E4E8ED;--bg3:#FFFFFF;--line:#CDD3DB;--text:#14111F;--muted:#4B5263;--subtle:#6B7280;
--prim:#00707F;--acc:#8A6100;--ok:#17703A;--flag:#B42318;--sunk:#0B0913;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#14111F;--bg2:#1E1A2E;--bg3:#0B0913;--line:#2A2440;
--text:#ECEEF4;--muted:#9EA3B8;--subtle:#8A8FA6;--prim:#00E5FF;--acc:#FFD23F;--ok:#5BD98A;--flag:#FF8A8A}}
:root[data-theme="dark"]{--bg:#14111F;--bg2:#1E1A2E;--bg3:#0B0913;--line:#2A2440;--text:#ECEEF4;--muted:#9EA3B8;
--subtle:#8A8FA6;--prim:#00E5FF;--acc:#FFD23F;--ok:#5BD98A;--flag:#FF8A8A}
*{box-sizing:border-box}
html{scroll-padding-top:72px}
body{margin:0;background:var(--bg);color:var(--text);font:400 16px/1.65 'Atkinson Hyperlegible Next',system-ui,sans-serif;
-webkit-font-smoothing:antialiased}
a{color:var(--prim);text-underline-offset:3px}
a:focus-visible,button:focus-visible,summary:focus-visible{outline:2px solid var(--prim);outline-offset:3px}
code,pre,.mono{font-family:'JetBrains Mono',ui-monospace,monospace;font-size:.86em}
.bar{position:sticky;top:0;z-index:5;display:flex;align-items:center;gap:24px;padding:12px 32px;background:var(--bg);
border-bottom:1px solid var(--line)}
.bar .brand{display:flex;align-items:center;gap:10px;font-weight:700;text-decoration:none;color:var(--text);white-space:nowrap}
.bar nav{display:flex;gap:18px;overflow-x:auto;scrollbar-width:none;flex:1;min-width:0}
.bar nav a{color:var(--muted);text-decoration:none;font-size:14px;white-space:nowrap}
.bar nav a:hover{color:var(--text)}
.bar button{font:inherit;font-size:14px;background:none;color:var(--muted);border:1px solid var(--line);padding:4px 10px;cursor:pointer;white-space:nowrap}
.bar .home{font-size:14px;color:var(--muted);text-decoration:none;white-space:nowrap}.bar .home:hover{color:var(--text)}
.hero{background:#14111F;color:#ECEEF4;position:relative}
.hero .media{position:relative;aspect-ratio:16/9;max-height:78vh;width:100%;overflow:hidden;background:#14111F}
.hero .media video,.hero .media img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.hero .media img{display:none}
@media (prefers-reduced-motion:reduce){.hero .media video{display:none}.hero .media img{display:block}}
.hero .intro{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr);gap:48px;padding:56px 32px 64px;max-width:1280px;margin:0 auto}
.hero h1{margin:0 0 20px;font-size:0;line-height:0}
.hero h1 svg{width:min(100%,620px);height:auto}
.hero p{margin:0;color:#9EA3B8;font-size:19px;max-width:56ch}
.hero dl{margin:0;display:grid;grid-template-columns:auto 1fr;gap:10px 24px;align-content:start;font-size:15px}
.hero dt{color:#8A8FA6}.hero dd{margin:0}
main{max-width:1280px;margin:0 auto;padding:0 32px 96px}
.sec{display:grid;grid-template-columns:240px minmax(0,1fr);gap:48px;padding:72px 0;border-top:1px solid var(--line)}
.sec:first-child{border-top:0}
.rail h2{position:sticky;top:84px;margin:0;font:700 33px/1.15 'Pixelify Sans',monospace;letter-spacing:-.0286em}
.rail h2::before{content:"";display:block;width:12px;height:12px;background:var(--acc);margin-bottom:14px}
.rail p{color:var(--muted);font-size:15px;margin:12px 0 0}
.body>*:first-child{margin-top:0}
.body h3{font:700 22px/1.3 'Pixelify Sans',monospace;margin:48px 0 10px}
.body p{max-width:70ch}
.lede{font-size:19px;color:var(--muted)}
figure{margin:24px 0 0}
figcaption{font-size:14px;color:var(--muted);margin-top:10px;max-width:80ch}
figcaption b{color:var(--text);font-weight:700}
.well{background:var(--sunk);padding:24px;overflow-x:auto}
.well.lt{background:#F3F5F7}
.well.mc{background:#101012;padding:16px}
.px{image-rendering:pixelated;display:block;max-width:none}
.row{display:flex;gap:32px;align-items:flex-end;flex-wrap:wrap}
.row figure{margin:0}
.duo{display:grid;grid-template-columns:1fr 1fr;gap:0}
.duo>div{padding:28px;display:flex;flex-direction:column;gap:22px;align-items:flex-start;overflow-x:auto}
.duo .dk{background:#14111F}.duo .lt{background:#F3F5F7}
.ladder{display:grid;grid-template-columns:repeat(6,auto);gap:28px;align-items:end;justify-content:start}
.ladder span{display:block;font-size:12px;color:#8A8FA6;margin-top:8px;font-family:'JetBrains Mono',monospace}
.ladder .off{opacity:.3}
table{border-collapse:collapse;width:100%;font-size:15px}
th,td{text-align:left;vertical-align:top;padding:12px 16px 12px 0;border-bottom:1px solid var(--line)}
thead th{font-size:13px;color:var(--subtle);font-weight:700;border-bottom:1px solid var(--text)}
.tw{overflow-x:auto}
.swatch{display:grid;grid-template-columns:repeat(3,1fr);height:84px}
.swatch div{display:flex;align-items:flex-end;padding:10px 12px;font-family:'JetBrains Mono',monospace;font-size:12px}
.colors{display:grid;gap:28px}
.colors .meta{display:grid;grid-template-columns:200px 1fr;gap:16px;margin-top:10px;font-size:14px}
.colors .meta b{font-size:16px}
.colors .meta p{margin:0;color:var(--muted)}
.dscale .s,.scale .s{display:grid;grid-template-columns:110px minmax(0,1fr);gap:20px;align-items:baseline;padding:10px 0;border-bottom:1px solid var(--line)}
.dscale .s code,.scale .s code{color:var(--subtle)}
.dscale .s div,.scale .s div{overflow-wrap:anywhere}
.spaces{display:grid;gap:8px}
.spaces div{display:grid;grid-template-columns:70px 1fr;gap:16px;align-items:center;font-size:13px}
.spaces i{display:block;height:12px;background:var(--prim)}
.elev{display:flex;gap:40px;flex-wrap:wrap}
.elev div{width:150px;height:96px;background:var(--bg2);border:1px solid var(--line);display:flex;align-items:flex-end;padding:10px;font-size:13px}
.facts{display:grid;grid-template-columns:1fr 1fr;gap:28px;margin-top:16px;font-size:14px;color:var(--muted)}
.facts h4{margin:0 0 6px;font-size:14px}
.facts h4.ok{color:var(--ok)}.facts h4.ap{color:var(--flag)}
.facts ul{margin:0;padding-left:18px}
.facts li{margin:3px 0}
.tag{display:inline-block;font-size:13px;font-weight:700;padding:1px 8px;background:var(--acc);color:#14111F;vertical-align:middle;margin-left:8px}
.tag.plan{background:transparent;color:var(--acc);border:1px solid var(--acc)}
pre.code,pre.console{background:var(--sunk);color:#D6D9E4;padding:20px 24px;overflow-x:auto;line-height:1.5;margin:20px 0 0;font-size:13px}
pre.console{font-size:13px;line-height:1.35;border-top:28px solid #1E1A2E;position:relative}
.clear{position:relative;display:inline-block}
.ex li{margin:6px 0}.ex .no{color:var(--subtle);text-decoration:line-through;text-decoration-color:var(--flag)}
.tone{display:grid;grid-template-columns:1fr 1fr;gap:40px}
.tone h3{margin-top:0}
.files{columns:2;font-size:13px;font-family:'JetBrains Mono',monospace;color:var(--muted);padding-left:18px}
.lang{border-left:2px solid var(--acc);padding-left:16px;margin-top:20px}
.at600{width:600px;max-width:100%;height:auto;display:block}
.fit{max-width:100%;height:auto;display:block}
.principles{display:grid;grid-template-columns:200px minmax(0,1fr);gap:14px 28px;margin:28px 0 0}
.principles dt{font:700 22px/1.3 'Pixelify Sans',monospace}
.principles dd{margin:0;color:var(--muted);max-width:62ch}
.rules{padding-left:18px}.rules li{margin:10px 0;max-width:72ch}
@media (max-width:900px){.principles{grid-template-columns:minmax(0,1fr);gap:4px}.principles dd{margin-bottom:14px}}
.initial{margin:0;font:700 44px/1.2 'Pixelify Sans',monospace;letter-spacing:-.0286em}
.initial img{height:1.6cap;width:1.6cap;vertical-align:-0.6cap;margin-right:.04em}
.wide{width:100%;height:auto;display:block}
.vid{width:100%;height:auto;display:block;background:#0B0913}
@media (max-width:900px){.sec{grid-template-columns:minmax(0,1fr);gap:20px;padding:48px 0}.rail h2{position:static}
.hero .intro{grid-template-columns:minmax(0,1fr);gap:28px;padding:36px 16px 44px}main{padding:0 16px 72px}
.bar{padding:10px 16px;gap:14px}.duo,.facts,.tone{grid-template-columns:minmax(0,1fr)}.colors .meta{grid-template-columns:minmax(0,1fr)}
.files{columns:1}.ladder{grid-template-columns:repeat(3,auto)}.well{padding:16px}}
.jump{display:none;font:inherit;font-size:14px;background:var(--bg);color:var(--text);border:1px solid var(--line);padding:4px 6px;max-width:44vw}
@media (max-width:560px){.bar nav{display:none}.bar{justify-content:space-between;gap:8px}.jump{display:block;flex:1;max-width:none;min-width:0}.bar .brand span{display:none}.bar button{padding:4px 6px}.bar .home{font-size:13px}}
.note-box{border-left:2px solid var(--line);padding:4px 0 4px 18px;margin:0 0 8px;color:var(--muted);font-size:14px}
.note-box h3{margin-top:0;color:var(--text)}.note-box ul{padding-left:18px}
"""

JS = r"""
(function(){var b=document.getElementById('theme'),r=document.documentElement,o=['auto','light','dark'],
l={auto:'Theme: system',light:'Theme: light',dark:'Theme: dark'},c='auto';
try{c=localStorage.getItem('vl-theme')||'auto'}catch(e){}
function a(){if(c==='auto')r.removeAttribute('data-theme');else r.setAttribute('data-theme',c);b.textContent=l[c]}
a();b.addEventListener('click',function(){c=o[(o.indexOf(c)+1)%3];try{localStorage.setItem('vl-theme',c)}catch(e){}a()});
var v=document.querySelector('.hero video');if(v&&window.matchMedia('(prefers-reduced-motion: reduce)').matches){v.removeAttribute('autoplay');v.pause()}})();
"""


def sec(id_, title, note, body):
    return (f'<section class="sec" id="{id_}" aria-labelledby="h-{id_}"><div class="rail"><h2 id="h-{id_}">{E(title)}</h2>'
            f'{("<p>" + note + "</p>") if note else ""}</div><div class="body">{body}</div></section>')


def construction():
    """Construction grid 8 x 8 with dimensions in cells."""
    s = 44
    cells = "".join(f'<rect x="{c * s}" y="{r * s}" width="{s}" height="{s}" fill="{"#00E5FF" if v == "X" else "#FFD23F"}"/>'
                    for r, row in enumerate(ga.MARK_A) for c, v in enumerate(row) if v != ".")
    grid = "".join(f'<path d="M{i * s} 0V{8 * s}M0 {i * s}H{8 * s}" stroke="#2A2440" stroke-width="1"/>' for i in range(9))
    t = lambda x, y, txt, a="start": f'<text x="{x}" y="{y}" fill="#9EA3B8" font-size="13" font-family="JetBrains Mono,monospace" text-anchor="{a}">{txt}</text>'
    br = lambda y0, y1, lab: (f'<path d="M{8 * s + 14} {y0}V{y1}M{8 * s + 9} {y0}H{8 * s + 19}M{8 * s + 9} {y1}H{8 * s + 19}" stroke="#9EA3B8"/>'
                             + t(8 * s + 26, (y0 + y1) / 2 + 4, lab))
    dim = (f'<path d="M0 -14H{2 * s}M0 -19V-9M{2 * s} -19V-9" stroke="#9EA3B8"/>' + t(s, -24, "stroke 2", "middle")
           + f'<path d="M{3 * s} {8 * s + 14}H{5 * s}M{3 * s} {8 * s + 9}V{8 * s + 19}M{5 * s} {8 * s + 9}V{8 * s + 19}" stroke="#9EA3B8"/>'
           + t(4 * s, 8 * s + 36, "tip 2 = block 2", "middle")
           + br(0, 5 * s, "V body 5") + br(5 * s, 6 * s, "gap 1") + br(6 * s, 8 * s, "block 2 x 2"))
    W = 8 * s
    return (f'<svg viewBox="-10 -44 {W + 140} {W + 100}" width="{W + 150}" height="{W + 100}" class="fit" role="img" '
            f'aria-label="Construction grid of the mark" shape-rendering="crispEdges">{cells}{grid}{dim}</svg>')


def clearspace(c, H=64, style="initial"):
    body, w = B.lockup(c, H, "product", style=style)
    m = H / 8
    pad = 2 * m
    W, Ht = w + 2 * pad, H + 2 * pad
    sq = "".join(f'<rect x="{x}" y="{y}" width="{pad}" height="{pad}" fill="#FFD23F" fill-opacity=".35"/>'
                 for x, y in ((0, 0), (W - pad, 0), (0, Ht - pad), (W - pad, Ht - pad)))
    box = f'<rect x="0.5" y="0.5" width="{W - 1}" height="{Ht - 1}" fill="none" stroke="#9EA3B8" stroke-dasharray="4 4"/>'
    u = 5 * m / 7
    if style == "initial":  # cap height and baseline
        gap = (f'<path d="M0 {pad}H{W}M0 {pad + 5 * m}H{W}" stroke="#00E5FF" stroke-opacity=".45" stroke-dasharray="2 3"/>'
               f'<rect x="{pad + 8 * m}" y="{pad}" width="{B.INITIAL_GAP * u:.2f}" height="{5 * m}" fill="#FFD23F" fill-opacity=".6"/>')
        label = "Clear space, mark as initial"
    else:
        gap = f'<rect x="{pad + 8 * m}" y="{pad}" width="{4 * m}" height="{H}" fill="#00E5FF" fill-opacity=".14"/>'
        label = "Clear space, mark with full name"
    return (f'<svg viewBox="0 0 {W:.0f} {Ht:.0f}" width="{W:.0f}" height="{Ht:.0f}" class="fit" role="img" aria-label="{label}">'
            f'{sq}{box}{gap}<g transform="translate({pad} {pad})">{body}</g></svg>')


def dont(kind, c=B.DARK, H=48):
    """Wrong uses, struck through."""
    m = H / 8
    u = 5 * m / 7
    body = B.mark(c, 0, 0, H)
    if kind == "double":
        x0, ty, txt = 8 * m + B.INITIAL_GAP * u, 0, "VECTOLYN"
    else:
        x0, ty, txt = 12 * m, (H - 7 * u) / 2, "ECTOLYN"
    d, w = B.vec_text(txt, x0, ty, u)
    W = x0 + w
    body += f'<path d="{d[None]}" fill="{c["text"]}"/>'
    body += f'<path d="M-4 {H + 2}L{W + 4} -2" stroke="#FF6B6B" stroke-width="3"/>'
    return (f'<svg viewBox="-6 -4 {W + 12:.0f} {H + 8:.0f}" width="{W + 12:.0f}" height="{H + 8:.0f}" class="fit" '
            f'role="img" aria-label="Wrong use">{body}</svg>')


def misuse(kind, H=56):
    """Wrong uses of the mark, struck through in red."""
    c = dict(B.DARK)
    defs, tr, extra = "", "", ""
    if kind == "color":
        c = dict(prim="#8B5CF6", acc="#22C55E")
        body = B.mark(c, 0, 0, H)
    elif kind == "gradient":
        defs = '<defs><linearGradient id="mg" x1="0" x2="1"><stop offset="0" stop-color="#00E5FF"/><stop offset="1" stop-color="#FF4FD8"/></linearGradient></defs>'
        body = B.mark(dict(prim="url(#mg)", acc="url(#mg)"), 0, 0, H)
    elif kind == "rotate":
        body = f'<g transform="rotate(-18 {H / 2} {H / 2})">{B.mark(c, 0, 0, H)}</g>'
    elif kind == "stretch":
        body = f'<g transform="translate(-10 0) scale(1.4 0.8)">{B.mark(c, 0, 8, H)}</g>'
    else:
        body = B.mark(dict(prim="none", acc="none"), 0, 0, H).replace('fill="none"', 'fill="none" stroke="#00E5FF" stroke-width="2"')
    cross = f'<path d="M-4 {H + 4}L{H + 4} -4" stroke="#FF6B6B" stroke-width="3"/>'
    return (f'<svg viewBox="-8 -8 {H + 16} {H + 16}" width="{H + 16}" height="{H + 16}" role="img" aria-label="Wrong use: {kind}">'
            f'{defs}{body}{cross}</svg>')


def page(f, mc, tokens, console):
    def mock(name, alt, scales, cap, fx, extra=""):
        src = f"{name}"
        imgs = "".join(f'<div class="well mc">{img(src, mc[name], k, alt + f", GUI scale {k}")}</div>'
                       f'{"<p class=note>GUI scale " + str(k) + "</p>" if len(scales) > 1 else ""}' for k in scales)
        return f'<figure>{imgs}<figcaption><b>{E(alt)}.</b> {cap}</figcaption>{extra}{facts(*fx)}</figure>'

    lad = ""
    for mode, bg in (("dark", "#14111F"), ("light", "#F3F5F7")):
        cells = ""
        for kind, sizes in (("mark", (16, 24, 32, 48, 64, 128)),):
            name = f'mark-on-{mode}.svg'
            for sz in sizes:
                cells += f'<div>{img(name, f[name], sz / 64, f"mark at {sz} px", "px")}<span>{sz} px</span></div>'
        lad += f'<div class="well{" lt" if mode == "light" else ""}"><div class="ladder">{cells}</div></div>'

    def lk(n, h=None):
        return img(n, f[n], (h / dims(f[n])[1]) if h else 1, n.replace(".svg", "").replace("-", " "), "fit")

    colors = [("Signal cyan", "primary", C["cyan"], C["cyan_l"], "VectolynCloud, links, the mark. In game #00E5FF; §b on legacy and Bedrock."),
              ("Spawn gold", "accent", C["gold"], C["gold_l"], "The network, the dot of .NET and .CLOUD, the block. In game #FFD23F; §6 legacy, §p Bedrock."),
              ("Obsidian", "surface", C["obsidian"], C["quartz"], "Backgrounds. Raised panels #1E1A2E, sunken wells #0B0913. Light theme: quartz #F3F5F7."),
              ("Text", "text", C["text_d"], C["text_l"], "Body text; muted #9EA3B8 / #4B5263 for secondary lines.")]
    col_html = ""
    for name, role, dk, lt, note in colors:
        cd = ga.contrast(dk, C["obsidian"]) if role != "surface" else None
        cl = ga.contrast(lt, C["quartz"]) if role != "surface" else None
        fg = lambda h: "#14111F" if ga.lum(h) > 0.4 else "#ECEEF4"
        col_html += (f'<div><div class="swatch"><div style="background:{dk};color:{fg(dk)}">{dk}</div>'
                     f'<div style="background:{lt};color:{fg(lt)}">{lt}</div>' +
                     (f'<div style="background:{C["obsidian"]};color:{dk}">on obsidian</div>' if role in ("primary", "accent") else "") + '</div>'
                     f'<div class="meta"><b>{name}</b><p><code>color.{role}</code>. {E(note)}'
                     + (f' Contrast {cd:.1f}:1 on dark, {cl:.1f}:1 on light.' if cd else "") + '</p></div></div>')
    status = "".join(f'<tr><th scope="row">{n}</th><td><code>{d}</code></td><td><code>{l}</code></td><td>{ga.contrast(d, C["obsidian"]):.1f} / {ga.contrast(l, C["quartz"]):.1f}</td></tr>'
                     for n, d, l in (("Success", C["success_d"], C["success_l"]), ("Warning", C["warn_d"], C["warn_l"]),
                                     ("Error", C["error_d"], C["error_l"]), ("Muted text", C["muted_d"], C["muted_l"])))
    scale = "".join(f'<div class="s"><code>{k} {v["size"]}</code><div style="font-size:{v["size"]};line-height:{v["line"]};'
                    f'font-weight:{800 if k in ("display", "5xl", "4xl", "3xl") else 700 if k in ("2xl", "xl") else 400};'
                    f'letter-spacing:{"-0.02em" if k in ("display", "5xl", "4xl") else "0"}">Your own Minecraft network</div></div>'
                    for k, v in reversed(list(tokens["font"]["scale"].items())) if isinstance(v, dict) and k not in ("display", "5xl"))
    dscale = "".join(f'<div class="s"><code>{k} {v["size"]}</code><div style="font:700 {v["size"]}/1.2 Pixelify Sans,monospace;letter-spacing:{"-0.0286em" if int(v["size"][:-2]) >= 33 else "0"}">Your own network</div></div>'
                     for k, v in reversed(list(tokens["font"]["display-scale"].items())) if isinstance(v, dict))
    spaces = "".join(f'<div><code>space.{k}</code><i style="width:{v}"></i></div>' for k, v in tokens["space"].items()
                     if not k.startswith("$") and k != "0")
    tone = "".join(f'<div><h3>{E(t)}</h3><ul>{"".join(f"<li>{E(x)}</li>" for x in rules)}</ul><ul class="ex">'
                   + "".join(f'<li class="{"yes" if ok else "no"}">{E(x)}</li>' for x, ok in ex) + '</ul></div>'
                   for t, rules, ex in TONE)
    spelling = "".join(f"<tr><th scope='row'>{E(a)}</th><td><code>{E(b)}</code></td><td>{E(c)}</td><td>{E(d)}</td></tr>"
                       for a, b, c, d in SPELLING)
    def dl(n, label=None):
        return f'<li><a href="{E(n)}" download>{E(label or n)}</a></li>'
    files = "".join(dl(n) for n in sorted(k for k in f if not k.startswith("layers/") and not k.startswith("mc-")))
    files += "".join(dl(n.split(" ")[0], n) for n in ("png/social-preview.png (+@2x)", "png/release-banner-v0.2.0-dev.png (+@2x)", "png/release-banner-background.png (+@2x)",
                                                  "png/readme-header-dark.png (+@2x)", "png/readme-header-light.png (+@2x)",
                                                  "png/keyart-3840x2160.png (+.jpg, 1920 x 1080 .jpg)", "png/mark-3d-2048.png (+512)", "png/server-icon.png", "png/favicon-48.png", "png/apple-touch-icon.png",
                                                  "png/favicon-16.png", "png/favicon-32.png", "video/mark-turntable.mp4",
                                                  "video/mark-turntable.webm"))
    files_mc = "".join(dl(n) for n in sorted(k for k in f if k.startswith("mc-")))
    files_mc += "".join(dl("png/" + k.replace(".svg", ".png"), "png/" + k.replace(".svg", ".png") + " (GUI scale 2, @2x = 4)")
                        for k in sorted(k for k in f if k.startswith("mc-")))
    hero_lockup = inline(f["lockup-vectolyncloud-dark.svg"], cls="")
    fav = "mark-on-dark.svg"
    readme_snip = E('<picture>\n  <source media="(prefers-color-scheme: dark)" srcset="branding/png/readme-header-dark@2x.png">\n'
                    '  <img alt="VectolynCloud" src="branding/png/readme-header-light@2x.png" width="1280">\n</picture>')

    S = []
    S.append(sec("overview", "Overview", "What Vectolyn is and the three ideas the identity is built on.", f'''
<p class="lede">Vectolyn is an open source toolkit for running your own Minecraft network. VectolynCloud starts and watches the servers; plugins, templates and a resource pack come with it. Operators meet the brand on GitHub and in the console, players meet it in the server list and the tab list.</p>
<dl class="principles">
<dt>Built from blocks</dt><dd>The mark is drawn on an 8 by 8 grid, the type is Pixelify Sans on a 7-row pixel grid, corners are square and shadows are hard offsets. Everything could be built in the game.</dd>
<dt>Two signals</dt><dd>Cyan stands for the software, gold for the network and the player's spawn point. Both sit on obsidian. No third accent color, no gradients on the mark.</dd>
<dt>Exact in game</dt><dd>In-game views are built to the client's own metrics: advance widths, shadows, GUI scale. Where a value is not verified, the page says so next to the image.</dd>
</dl>'''))
    S.append(sec("mark", "The mark", "A V built from eight by eight blocks, with a gold block below it: the spawn point of the network.", f'''
<p class="lede">One mark, one construction: a V built from whole blocks on an 8 by 8 grid, with the gold block below it. It is drawn the same way at every size, from the 16 px favicon to the 3D key art, always on whole pixels.</p>
<figure><div class="well">{construction()}</div><figcaption><b>Construction.</b> Arms are 2 cells wide and step in by one cell per row. The V narrows to a tip of 2 cells, exactly above the 2 by 2 gold block, with one empty row between them. The V body is 5 rows tall, the cap height of the wordmark.</figcaption></figure>
<figure>{lad}<figcaption><b>Size rule.</b> The pixel construction at every size. Export at multiples of 8 px (16, 24, 32, 48, 64, 128) so every cell lands on whole screen pixels, with <code>shape-rendering="crispEdges"</code> and no resampling.</figcaption></figure>
<h3>Minimum sizes</h3>
<div class="tw"><table><thead><tr><th scope="col">Asset</th><th scope="col">Minimum</th><th scope="col">Below that</th></tr></thead><tbody>
<tr><th scope="row">Mark alone</th><td>16 px</td><td>Do not use; there is no smaller drawing.</td></tr>
<tr><th scope="row">Lockup</th><td>32 px mark height</td><td>Use the mark alone and set the name as text (case 3).</td></tr>
<tr><th scope="row">Pixelify Sans headline</th><td>22 px</td><td>Use Atkinson Hyperlegible Next 700.</td></tr>
</tbody></table></div>
<figure><div class="well"><div class="row">{misuse("color")}{misuse("gradient")}{misuse("rotate")}{misuse("stretch")}{misuse("outline")}</div></div><figcaption><b>Don't.</b> Recolor the mark, fill it with a gradient, rotate it, stretch it or draw it as an outline.</figcaption></figure>'''))

    S.append(sec("wordmark", "Wordmark and lockups", "Pixelify Sans 700, outlined, with the mark as its first letter.", f'''
<p>The wordmark is Pixelify Sans at weight 700, outlined to paths. Its capitals are seven font pixels tall, and the V body of the mark is drawn to the same height, so the mark can stand in for the V. Letters are one font pixel apart (tracking -0.0286 em). VECTOLYN and CLOUD form one word: the same one-pixel gap, separated only by color.</p>
<h3>Three ways to write the name</h3>
<div class="tw"><table><thead><tr><th scope="col">Case</th><th scope="col">When</th><th scope="col">How</th></tr></thead><tbody>
<tr><th scope="row">1. Mark as initial</th><td>The mark sits directly before the name: lockups, headers, banners, the tab list with our resource pack</td><td>The mark is the V. Write <code>[mark]ECTOLYN</code>, <code>[mark]ECTOLYN.NET</code>, <code>[mark]ectolynCloud</code>. Never the mark followed by a full VECTOLYN.</td></tr>
<tr><th scope="row">2. Mark on its own</th><td>Favicon, app icon, server icon, avatar, the 3D mark in a banner, the stacked lockup</td><td>The mark stands as a symbol. If the name follows, it is spelled in full, at least 4 units away (two gold blocks) and centered on the mark.</td></tr>
<tr><th scope="row">3. Text only</th><td>No mark available: plain text, chat, the MOTD, console lines, the ASCII banner, documentation</td><td>Full name, no mark: <code>VECTOLYN</code>, <code>VECTOLYN.NET</code>, <code>VectolynCloud</code>.</td></tr>
</tbody></table></div>
<figure><div class="well">{clearspace(B.DARK)}</div><figcaption><b>1. Mark as initial.</b> Cap height of the wordmark equals the body of the V (5 units). Top line and baseline align (dashed), and the gold block hangs below like a descender. The space to the E is one font pixel (gold), the same as between all other letters. Clear space on every side is 2 units, the size of the gold block.</figcaption></figure>
<figure><div class="duo"><div class="dk">{lk("lockup-vectolyncloud-dark.svg", 44)}{lk("lockup-vectolyn-net-dark.svg", 44)}{lk("lockup-vectolyn-cloud-dot-dark.svg", 44)}{lk("lockup-vectolyncloud-mono-white.svg", 44)}</div>
<div class="lt">{lk("lockup-vectolyncloud-light.svg", 44)}{lk("lockup-vectolyn-net-light.svg", 44)}{lk("lockup-vectolyn-cloud-dot-light.svg", 44)}{lk("lockup-vectolyncloud-mono-black.svg", 44)}</div></div>
<figcaption><b>Primary lockups, mark as initial.</b> VECTOLYNCLOUD as one word with CLOUD in cyan. VECTOLYN.NET and VECTOLYN.CLOUD with the suffix in gold. One-color versions below.</figcaption></figure>
<figure><div class="duo"><div class="dk"><p class="initial" style="color:#ECEEF4"><img src="mark-on-dark.svg" alt="V">ectolynCloud</p></div><div class="lt"><p class="initial" style="color:#14111F"><img src="mark-on-light.svg" alt="V">ectolynCloud</p></div></div>
<figcaption><b>Initial in running type.</b> Where the name is set as live text in Pixelify Sans (website header, dashboard), scale the mark so the V body matches the cap height and its base sits on the baseline. In CSS: height 1.6cap, vertical-align -0.6cap.</figcaption></figure>
<figure><div class="well">{clearspace(B.DARK, style="symbol")}</div><figcaption><b>2. Mark with full name.</b> 4 units between mark and name (cyan), name centered on the mark. Use it where the mark has to stay a separate symbol.</figcaption></figure>
<figure><div class="duo"><div class="dk">{lk("lockup-symbol-vectolyncloud-dark.svg", 44)}{lk("lockup-symbol-vectolyn-net-dark.svg", 44)}</div><div class="lt">{lk("lockup-symbol-vectolyncloud-light.svg", 44)}{lk("lockup-symbol-vectolyn-net-light.svg", 44)}</div></div>
<figcaption><b>Secondary lockups, mark with full name.</b></figcaption></figure>
<figure><div class="well"><div class="row">{dont("double")}{dont("apart")}</div></div><figcaption><b>Don't.</b> Left: the mark followed by VECTOLYN reads as VVECTOLYN. Right: ECTOLYN at symbol distance, so the mark no longer reads as its first letter.</figcaption></figure>
<figure><div class="duo"><div class="dk">{lk("lockup-stacked-dark.svg", 180)}</div><div class="lt">{lk("lockup-stacked-light.svg", 180)}</div></div>
<figcaption><b>Stacked lockup (mark on its own, full name below).</b> For square formats: avatars, stickers, the splash screen of the dashboard.</figcaption></figure>
<figure><div class="duo"><div class="dk">{img("lockup-vectolyncloud-32-dark.svg", f["lockup-vectolyncloud-32-dark.svg"], 1, "pixel lockup")}{img("lockup-vectolyn-net-32-dark.svg", f["lockup-vectolyn-net-32-dark.svg"], 1, "pixel lockup .NET")}</div>
<div class="lt">{img("lockup-vectolyncloud-32-light.svg", f["lockup-vectolyncloud-32-light.svg"], 1, "pixel lockup light")}{img("lockup-vectolyn-net-32-light.svg", f["lockup-vectolyn-net-32-light.svg"], 1, "pixel lockup .NET light")}</div></div>
<figcaption><b>Lockups at 32 px, mark as initial.</b> Mark cell 4 px, letter pixel 3 px. The letters are 21 px tall and the V body 20 px, so the mark sits 1 px lower to share the baseline. For small UI and the resource pack.</figcaption></figure>'''))

    S.append(sec("color", "Color", "Two signals on obsidian. Cyan is the product, gold is the network.", f'''
<div class="colors">{col_html}</div>
<h3>Status and secondary</h3><div class="tw"><table><thead><tr><th scope="col">Role</th><th scope="col">Dark</th><th scope="col">Light</th><th scope="col">Contrast dark / light</th></tr></thead><tbody>{status}</tbody></table></div>
<p class="note" style="color:var(--muted);font-size:14px">Contrast is WCAG 2.2 against the surface of each theme. Every value lives in tokens.json and tokens.css.</p>'''))

    S.append(sec("type", "Typography", "Pixelify Sans for the name and headlines, Atkinson Hyperlegible Next for reading, JetBrains Mono for typing.", f'''
<p>Pixelify Sans is the brand typeface. It sets the wordmark, the headlines on banners and the headings of this page, always at weight 700. Its letters sit on a pixel grid of seven rows, the same height as the body of the V. Atkinson Hyperlegible Next carries body text and UI, because it separates I, l and 1, and O and 0, which matters for addresses and version numbers. JetBrains Mono is for anything you type. All three are under the SIL Open Font License; self-host them on the website.</p>
<h3>Pixelify Sans on the pixel grid</h3>
<p>One font pixel is 0.09143 em. At 22, 33, 44, 66 and 88 px it lands on 2, 3, 4, 6 and 8 screen pixels, so the steps stay sharp. From 33 px up, track it to -0.0286 em: the letters are then exactly one font pixel apart, as in the wordmark. In artwork the type is outlined to paths, never scaled from a bitmap.</p>
<div class="dscale">{dscale}</div>
<h3>Text scale, Atkinson Hyperlegible Next</h3>
<div class="scale">{scale}</div>
<figure><pre class="code"><code>java -jar VectolynCloud.jar
docker pull ghcr.io/vectode/vectolyn-cloud:0.2.0</code></pre><figcaption><b>Mono.</b> JetBrains Mono for commands, paths, ports and the console.</figcaption></figure>'''))

    S.append(sec("system", "Space, shape, depth", "A 4 px grid, square corners and hard shadows, like blocks.", f'''
<div class="spaces">{spaces}</div>
<h3>Radius and elevation</h3><p>Every radius is 0. Depth comes from hard offsets, never from soft blur. The one exception is glow: it belongs to light sources, the 3D mark and keyboard focus.</p>
<div class="elev"><div style="box-shadow:none">elevation.0</div><div style="box-shadow:4px 4px 0 #0B0913">elevation.1</div><div style="box-shadow:8px 8px 0 #0B0913">elevation.2</div><div style="box-shadow:0 0 0 1px rgba(0,229,255,.35),0 0 32px rgba(0,229,255,.18)">glow</div></div>'''))
    S.append(sec("pixels", "Pixel rules and icons", "How pixel art stays sharp on every screen.", f'''
<ul class="rules">
<li><b>Whole-number scaling only.</b> Pixel art is shown at 1x, 2x, 3x or 4x with <code>image-rendering: pixelated</code>. Never at 1.5x, never smoothed.</li>
<li><b>Draw on the grid.</b> Every edge of the mark, the server icon and pixel icons sits on a whole pixel. SVGs carry <code>shape-rendering="crispEdges"</code>.</li>
<li><b>No rotation, no perspective</b> for flat pixel art. Depth comes from stepped offsets, as in the 64 px server icon.</li>
<li><b>Icons.</b> UI icons use a 16 by 16 grid with 2 px strokes, square ends and no curves. One color per icon: text color, cyan for active, gold only for the network. No emoji.</li>
<li><b>Pixelify Sans</b> at 22, 33, 44, 66 or 88 px, where one font pixel lands on whole screen pixels.</li>
<li><b>3D is the exception.</b> The Cycles renders show the same construction as an object; they may be scaled freely, but they never replace the flat mark in small sizes.</li>
</ul>
<figure><div class="well"><div class="row">{img("server-icon-64.svg", f["server-icon-64.svg"], 2, "Server icon at 2x")}{img("server-icon-64.svg", f["server-icon-64.svg"], 1, "Server icon at 1x")}</div></div><figcaption><b>Server icon, 64 x 64.</b> Drawn pixel by pixel, depth as a 2 px step. Shown at 2x and 1x.</figcaption></figure>'''))

    motd = E(f["motd.txt"])
    tabyml = E(f["tab-vectolyn.yml"])
    S.append(sec("ingame", "In game", "Every view is built in GUI pixels and scaled only by whole numbers. Each one says what is exact and what is not.", f'''
<div class="note-box"><h3>Fidelity notes for every view below</h3><ul><li>{E(GLYPHS)}</li><li>{E(SYMBOLS)}</li><li>{E(PING)}</li><li>{E(MODERN)}</li></ul><p>Each figure lists only what is specific to it.</p></div>
<h3>Server list</h3>
<p>Our entry between placeholder networks, selected. Line 1 carries the brand and the version range, line 2 the news. Both lines are centered with counted spaces, the way networks do it.</p>
{mock("mc-serverlist-java.svg", "Java 1.20.5 and later", (3, 2), "Hex colors, text shadow and selection frame as on a current client. Neighbours are placeholders, not real networks.", F_SERVER)}
{mock("mc-serverlist-java18.svg", "Java 1.8.9", (2,), "Planned MOTD with § colors from our own proxy plugin (stage 3). Until then Velocity shows cyan as §3 dark aqua.", F_SERVER18)}
{mock("mc-serverlist-bedrock.svg", "Bedrock through Geyser", (2,), "Approximation of the Bedrock list row.", F_BEDROCK)}
<pre class="code"><code>{motd}</code></pre>
<h3>Tab list, as configured today</h3>
<p>This is the list players see now: the TAB plugin with the config shipped in the server templates. Placeholders are filled with example values.</p>
{mock("mc-tablist-tab-java.svg", "TAB design default, Java 1.20.3 and later", (2,), "Header with the white-to-yellow rule, Server name, typing animation, online counts; ping as fancy value next to each name.", F_TAB)}
{mock("mc-tablist-tab-java18.svg", "TAB design default, Java 1.8.9", (2,), "Hex colors reduced to § colors; ping as yellow number.", F_TAB)}
<h3>Tab list, Vectolyn branding</h3>
<p>The same structure in our colors, with the full footer. This is the design the cloud's own plugin will send later; until then it works as a TAB design.</p>
{mock("mc-tablist-vectolyn-java.svg", "Vectolyn branding, Java 1.20.3 and later", (2,), "Mark glyph from the resource pack, gradient rules from cyan to gold, ranks in their colors, sorted by rank. Footer frame 1 of the time animation.",
      F_TABV, '<div class="well mc" style="margin-top:12px">' + img("mc-tablist-vectolyn-java-date.svg", mc["mc-tablist-vectolyn-java-date.svg"], 2, "Footer, frame 2 of the time animation") + '</div><figcaption>Footer, frame 2 of the same animation: date instead of time.</figcaption>')}
{mock("mc-tablist-vectolyn-java18.svg", "Vectolyn branding, Java 1.8.9", (2,), "§ colors, dark gray rules, no glyph.", F_TABV)}
<h3>Tab list on Bedrock <span class="tag plan">Planned, stage 3</span></h3>
<p>Vanilla Geyser shows Bedrock players no tab header or footer today; it drops the packet. Stage 3 adds a Vectolyn tab list for Bedrock (own plugin plus a Bedrock resource pack with JSON UI, or a Geyser extension). This is the target design, not current behavior.</p>
{mock("mc-tablist-vectolyn-bedrock-planned.svg", "Planned: Vectolyn tab list for Bedrock", (2,), "Same content in a Bedrock-native panel and Bedrock colors.", F_BTAB)}
<pre class="code"><code>{tabyml}</code></pre>
<h3>/version and the powered-by line</h3>
<p>On by default, switchable for other operators (<code>branding.powered-by: true</code> in <code>cloud.yml</code>). It appears as the last tab footer line and in <code>/version</code>, never in the MOTD, broadcasts or scoreboards. English is the default; other languages follow the client where VectolynCloud offers a language choice.</p>
{mock("mc-chat-version.svg", "Chat, /version on Java", (2,), "English default.", F_CHAT)}
{mock("mc-chat-version-bedrock.svg", "Chat, /version on Bedrock", (2,), "Plain text, the link is not clickable.", F_BCHAT)}
<div class="lang"><p><b>Language option, German.</b> Shown only where a player or operator picked German.</p><div class="well mc">{img("mc-chat-version-de.svg", mc["mc-chat-version-de.svg"], 2, "Chat /version, German option")}</div>
<p style="font-size:14px;color:var(--muted)">Tab footer in German: <code>Läuft mit VectolynCloud</code>.</p></div>
<h3>Console at startup</h3>
<p>Exactly the format of <code>StartupReport</code>: the banner on standard output, then one log line per step as <code>HH:mm:ss LEVEL message</code>. Values are examples. Plain ASCII, 68 columns wide; a narrow 55-column version is in console-banner.txt.</p>
<pre class="console" aria-label="Console output at startup">{E(console)}</pre>'''))

    S.append(sec("banners", "Imagery and 3D", "The mark as a physical object, rendered in Blender Cycles on the GPU. Type is set on top as vector paths.", f'''
<figure><img class="wide" src="png/social-preview@2x.png" width="1280" height="640" alt="VectolynCloud social preview"><figcaption><b>Social preview, 1280 x 640.</b> Upload png/social-preview.png to the repository settings.</figcaption></figure>
<figure><img class="at600" src="png/social-preview@2x.png" width="600" height="300" alt="Social preview at link card size"><figcaption><b>At link-card size, 600 px.</b> Wordmark and mark still read.</figcaption></figure>
<figure><img class="wide" src="png/release-banner-v0.2.0-dev@2x.png" width="1280" height="320" alt="Release banner v0.2.0-dev"><figcaption><b>Release banner, 1280 x 320.</b> Generated for every release; see the release banner system below.</figcaption></figure>
<figure><img class="wide" src="png/readme-header-dark@2x.png" width="1280" height="280" alt="README header, dark"><img class="wide" src="png/readme-header-light@2x.png" width="1280" height="280" alt="README header, light" style="margin-top:12px"><figcaption><b>README header, dark and light.</b></figcaption></figure>
<pre class="code"><code>{readme_snip}</code></pre>
<figure><video class="vid" src="video/mark-turntable.mp4" poster="png/keyart-1920x1080.jpg" controls muted loop playsinline preload="none"></video><figcaption><b>Turntable, 4 s loop.</b> MP4 (H.264) and WebM (VP9) in video/. Cyan lacquer with clear coat, polished gold, area lights with light linking, Khronos PBR Neutral view transform.</figcaption></figure>
<figure><div class="well" style="background:repeating-conic-gradient(#1E1A2E 0 25%,#14111F 0 50%) 0 0/24px 24px"><img src="png/mark-3d-2048.png" width="420" height="420" alt="3D mark on transparent background" style="display:block;margin:auto;max-width:100%;height:auto"></div><figcaption><b>3D mark, transparent, 2048 px.</b> For press and presentations. No floor shadow, so it sits on any background.</figcaption></figure>'''))

    cases = [("stable", "Stable release, v1.0.0: no badge"), ("dev", "v0.3.0-dev.2: PRE-RELEASE badge, gray"),
             ("alpha", "v1.1.0-alpha.1: ALPHA badge, red"), ("beta", "v1.1.0-beta.2: BETA badge, gold"),
             ("rc", "v1.1.0-rc.1: RC badge, cyan"), ("lts", "v1.4.0-lts: LTS badge, green"),
             ("notitle", "Empty tag message: the date moves into the title line"),
             ("long", "Long version and long title: version steps down to a smaller font pixel, title is cut at a word end")]
    rel_figs = "".join(f'<figure><img class="wide" src="png/release-example-{k}.png" width="1280" height="320" alt="Release banner example: {E(t)}" loading="lazy"><figcaption>{E(t)}.</figcaption></figure>' for k, t in cases)
    S.append(sec("releases", "Release banner system", "One background, rendered once. Per release only the type and the badge change.", f'''
<p>CI builds the banner for every tag from three inputs: the version, the first line of the tag message as the title, and the date. Everything is set in Pixelify Sans 700 and outlined to paths, on top of <code>png/release-banner-background.png</code> (1280 x 320, and 2560 x 640 as @2x).</p>
<div class="tw"><table><thead><tr><th scope="col">Slot</th><th scope="col">Type</th><th scope="col">Color</th><th scope="col">Rule</th></tr></thead><tbody>
<tr><th scope="row">wordmark</th><td>u 3 (cap 21 px)</td><td>#ECEEF4, CLOUD #00E5FF</td><td>VECTOLYNCLOUD as one word, top of the block</td></tr>
<tr><th scope="row">badge</th><td>u 3, padding 2u x 1u</td><td>text #14111F on the badge color</td><td>12 px after the wordmark, centered on its cap height; corners cut by one font pixel</td></tr>
<tr><th scope="row">version</th><td>u 12, then 10, 8, 6</td><td>#ECEEF4, points of MAJOR.MINOR.PATCH #FFD23F</td><td>The largest size that fits the 780 px text column</td></tr>
<tr><th scope="row">title</th><td>u 3</td><td>#ECEEF4</td><td>Max 48 characters, cut at a word end with an ellipsis; shortened further word by word if title and date exceed 780 px. Empty: line holds only the date</td></tr>
<tr><th scope="row">date</th><td>u 3</td><td>#9EA3B8</td><td>English, "Oct 30, 2026", after a 6 x 6 gold square</td></tr>
</tbody></table></div>
<p>Badges follow the version suffix: <code>-dev[.N]</code> PRE-RELEASE in gray #9EA3B8, <code>-alpha[.N]</code> ALPHA in red #FF6B6B, <code>-beta[.N]</code> BETA in gold, <code>-rc[.N]</code> RC in cyan, <code>-lts</code> LTS in green #5BD98A (the success color, 10.4:1 with the obsidian text). Stable versions carry no badge. Versions below 1.0.0 are released only as <code>-dev</code>; any other suffix is not a release tag and stops the release. The block of wordmark, version and title line is centered vertically, so a smaller version size keeps the balance. Template with named slots: <a href="release-banner-template.svg">release-banner-template.svg</a>; all values: <a href="release-banner-spec.json">release-banner-spec.json</a>.</p>
{rel_figs}'''))
    S.append(sec("writing", "Writing", "English by default. Other languages only where VectolynCloud offers a choice.", f'''
<div class="tw"><table><thead><tr><th scope="col">Where</th><th scope="col">Write</th><th scope="col">Avoid</th><th scope="col">Rule</th></tr></thead><tbody>{spelling}</tbody></table></div>
<h3>Voice and tone</h3><div class="tone">{tone}</div>'''))

    S.append(sec("open", "Not verified", "Open points before wider public use.", f'<ul>{"".join(f"<li>{E(x)}</li>" for x in UNVERIFIED)}</ul>'))
    S.append(sec("files", "Downloads", "Everything in branding/. Regenerate with branding/tools/gen_v3.py, render_all.sh and export_v3.py (see branding/tools/README.md).",
                 f'<h3>Brand assets</h3><ul class="files">{files}</ul><h3>In-game views</h3><ul class="files">{files_mc}</ul>'))

    nav = "".join(f'<a href="#{i}">{t}</a>' for i, t in (("overview", "Overview"), ("mark", "Mark"), ("wordmark", "Lockups"), ("color", "Color"), ("type", "Type"),
                                                        ("system", "System"), ("pixels", "Pixels"), ("ingame", "In game"), ("banners", "Imagery"), ("releases", "Releases"),
                                                        ("writing", "Voice"), ("open", "Not verified"), ("files", "Downloads")))
    jump = '<option value="top">Sections</option>' + "".join(f'<option value="{i}">{t}</option>' for i, t in re.findall(r'href="#([a-z]+)">([^<]+)<', nav))
    small = img("mark-on-dark.svg", f["mark-on-dark.svg"], 0.375, "", "px")
    # Optional "site" in brand.json: the project page the guide is published under; adds a link back to it.
    home = f'<a class="home" href="{E(ga.BRAND["site"])}">Project page</a>' if ga.BRAND.get("site") else ""
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vectolyn Brand Guidelines</title>
<meta name="description" content="Mark, wordmark, color, type, in-game design and banners for Vectolyn and VectolynCloud.">
<link rel="icon" href="{fav}" type="image/svg+xml">
<style>{CSS}</style>
</head>
<body>
<header class="bar"><a class="brand" href="#top" aria-label="Vectolyn brand guidelines, back to top">{small}<span style="margin-left:6px">Brand guidelines</span></a><nav aria-label="Sections">{nav}</nav><select class="jump" aria-label="Jump to section" onchange="location.hash=this.value">{jump}</select>{home}<button id="theme" type="button">Theme: system</button></header>
<div class="hero" id="top">
  <div class="media"><video autoplay muted loop playsinline poster="png/keyart-1920x1080.jpg" aria-label="The Vectolyn mark turning"><source src="video/mark-turntable.webm" type="video/webm"><source src="video/mark-turntable.mp4" type="video/mp4"></video>
  <img src="png/keyart-1920x1080.jpg" alt="The Vectolyn mark as a 3D object"></div>
  <div class="intro"><div><h1>{hero_lockup}<span style="position:absolute;left:-9999px">Vectolyn brand guidelines</span></h1>
  <p>How Vectolyn and VectolynCloud look and speak: the mark, the name, color and type, and every surface inside the game.</p></div>
  <dl><dt>Version</dt><dd>3</dd><dt>Date</dt><dd>30 October 2026</dd><dt>Colors</dt><dd><span style="color:#00E5FF">#00E5FF</span>, <span style="color:#FFD23F">#FFD23F</span> on #14111F</dd><dt>Type</dt><dd>Pixelify Sans, Atkinson Hyperlegible Next, JetBrains Mono</dd><dt>In game</dt><dd>VECTOLYN.NET, Java 1.8 to 26.3 and Bedrock</dd></dl></div>
</div>
<main>{"".join(S)}</main>
<script>{JS}</script>
</body>
</html>'''
