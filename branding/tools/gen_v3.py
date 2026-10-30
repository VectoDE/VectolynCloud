"""Vectolyn brand set, version 3: vector assets, in-game mockups, texts, tokens and the brand guide page.

Run from this folder (see README.md):
    uv run --no-project --with uharfbuzz==0.57.0 --with fonttools==4.68.0 --with pillow==13.0.0 python gen_v3.py [--out DIR]

Writes into branding/ by default, or into DIR. Files it does not generate (png/, video/, fonts/, tools/) are left alone.
Renders (render_mark.py) and PNG exports (export_v3.py) are separate steps."""
import json
import math
import os
import re
import sys

import brand3 as B
import content3 as K
import core as ga
import mcmock as MM
import mcui as U
import page3

OUT = str(ga.BRANDING)
if "--out" in sys.argv:
    OUT = os.path.abspath(sys.argv[sys.argv.index("--out") + 1])
C = B.C


def contrast(a, b):
    return round(ga.contrast(a, b), 2)


def tokens():
    def col(dark, light, desc, mc=None):
        o = {"$type": "color", "$description": desc,
             "dark": {"$value": dark, "contrast-on-surface": contrast(dark, C["obsidian"])},
             "light": {"$value": light, "contrast-on-surface": contrast(light, C["quartz"])}}
        if mc:
            o["minecraft"] = mc
        return o

    return {
        "$description": "Vectolyn design tokens, version 3 (2026-10-30). Contrast = WCAG 2.2 against color.surface.",
        "color": {
            "primitive": {
                "cyan-500": {"$type": "color", "$value": C["cyan"]}, "cyan-800": {"$type": "color", "$value": C["cyan_l"]},
                "gold-400": {"$type": "color", "$value": C["gold"]}, "gold-800": {"$type": "color", "$value": C["gold_l"]},
                "obsidian-950": {"$type": "color", "$value": C["ink"]},
                "obsidian-900": {"$type": "color", "$value": C["obsidian"]},
                "obsidian-800": {"$type": "color", "$value": C["obsidian2"]},
                "obsidian-700": {"$type": "color", "$value": C["obsidian3"]},
                "quartz-50": {"$type": "color", "$value": C["quartz"]}, "quartz-100": {"$type": "color", "$value": C["quartz2"]},
                "quartz-200": {"$type": "color", "$value": C["quartz3"]},
            },
            "surface": col(C["obsidian"], C["quartz"], "Page background"),
            "surface-raised": col(C["obsidian2"], C["quartz2"], "Panels, code, table heads"),
            "surface-sunken": col(C["ink"], "#FFFFFF", "Media wells, console"),
            "border": col(C["obsidian3"], C["quartz3"], "Rules and outlines, 1 px"),
            "text": col(C["text_d"], C["text_l"], "Body text"),
            "text-muted": col(C["muted_d"], C["muted_l"], "Secondary text"),
            "text-subtle": col(C["subtle_d"], C["subtle_l"], "Captions, metadata (large text or non-essential only)"),
            "primary": col(C["cyan"], C["cyan_l"], "Signal cyan: VectolynCloud, links, the mark",
                           {"java": "#00E5FF (1.16+)", "java-legacy": "§b aqua", "bedrock": "§b #54FFFF"}),
            "accent": col(C["gold"], C["gold_l"], "Spawn gold: the network, the dot of .NET/.CLOUD, highlights",
                          {"java": "#FFD23F (1.16+)", "java-legacy": "§6 gold", "bedrock": "§p #FFBF1E"}),
            "success": col(C["success_d"], C["success_l"], "Success"),
            "warning": col(C["warn_d"], C["warn_l"], "Warning"),
            "error": col(C["error_d"], C["error_l"], "Error"),
        },
        "font": {
            "family": {
                "text": {"$type": "fontFamily", "$value": ["Atkinson Hyperlegible Next", "system-ui", "sans-serif"]},
                "mono": {"$type": "fontFamily", "$value": ["JetBrains Mono", "ui-monospace", "monospace"]},
                "display": {"$type": "fontFamily", "$value": ["Pixelify Sans", "monospace"],
                            "$description": "Brand typeface: wordmark, headlines, banners, page headings. Weight 700. Outlined to paths in artwork."},
            },
            "weight": {"regular": 400, "bold": 700, "heavy": 800},
            "scale": {"$description": "Major third (1.25) from 16 px; line height for text roles",
                      "xs": {"size": "12px", "line": "16px"}, "sm": {"size": "14px", "line": "20px"},
                      "md": {"size": "16px", "line": "26px"}, "lg": {"size": "20px", "line": "30px"},
                      "xl": {"size": "25px", "line": "32px"}, "2xl": {"size": "31px", "line": "38px"},
                      "3xl": {"size": "39px", "line": "44px"}, "4xl": {"size": "49px", "line": "54px"},
                      "5xl": {"size": "61px", "line": "64px"}, "display": {"size": "76px", "line": "76px"}},
            "display-scale": {"$description": "Pixelify Sans sizes where one font pixel (0.09143 em) lands on whole screen pixels",
                              "d1": {"size": "22px", "pixel": "2px"}, "d2": {"size": "33px", "pixel": "3px"},
                              "d3": {"size": "44px", "pixel": "4px"}, "d4": {"size": "66px", "pixel": "6px"},
                              "d5": {"size": "88px", "pixel": "8px"}},
            "tracking": {"display": "-0.0286em", "display-small": "0", "text": "0",
                         "$description": "display: letter gap of exactly one font pixel, from 33 px up; below that the font's own spacing"},
        },
        "space": {"$description": "4 px base; the mark's grid unit scales with it",
                  **{str(k): f"{v}px" for k, v in
                     [(0, 0), (1, 4), (2, 8), (3, 12), (4, 16), (5, 24), (6, 32), (7, 48), (8, 64), (9, 96), (10, 128)]}},
        "radius": {"$description": "Square everywhere. The brand is built from blocks.", "none": "0px"},
        "border": {"width": {"hairline": "1px", "strong": "2px"}},
        "elevation": {"$description": "Hard pixel offsets instead of soft blur, like a block's shadow",
                      "0": "none", "1": f"4px 4px 0 {C['ink']}", "2": f"8px 8px 0 {C['ink']}",
                      "glow": "0 0 0 1px rgba(0,229,255,.35), 0 0 32px rgba(0,229,255,.18)"},
        "motion": {"duration": {"fast": "120ms", "base": "200ms", "slow": "400ms"},
                   "easing": {"standard": "cubic-bezier(.2,0,0,1)", "exit": "cubic-bezier(.4,0,1,1)"},
                   "reduced-motion": "Disable non-essential motion; show the still render instead of the turntable"},
        "breakpoint": {"sm": "480px", "md": "768px", "lg": "1024px", "xl": "1280px"},
        "mark": {
            "grid": "8 x 8 units; arms 2 units wide stepping in 1 per row, tip 2 units (rows 0-4), 1 unit gap, gold block 2 x 2 at rows 6-7 under the tip",
            "construction": {"use": "every size, one mark: favicon 16 px to key art", "rendering": "crisp edges, sizes in multiples of 8 px, no resampling"},
            "clearspace": "2 units (the size of the gold block) on every side",
            "lockup-initial": "mark replaces the V: [mark]ECTOLYN; cap height = V body (5 units), top and baseline aligned, gap to E one font pixel (as between letters); never [mark] followed by VECTOLYN",
            "lockup-symbol": "mark stands alone, 4 units clear space (two gold blocks), then the full name centered on the mark",
            "text-only": "no mark available (plain text, chat, MOTD without glyph, console, docs): full name VECTOLYN / VECTOLYN.NET / VectolynCloud",
            "clear-space": "2 units (one gold block) on every side",
        },
        "minecraft": {"server-icon": "64 x 64 PNG", "motd-centering": "area 270 px, space 4 px, bold +1 px",
                      "legacy-fallback": {"primary": "§b", "accent": "§6"}, "bedrock": {"primary": "§b", "accent": "§p"}},
    }


def tokens_css(t):
    c = t["color"]
    sem = [k for k in c if k != "primitive"]
    lines = lambda mode: "\n".join(f"  --vl-color-{k}: {c[k][mode]['$value']};" for k in sem)
    sc = t["font"]["scale"]
    other = "\n".join(
        [f"  --vl-font-text: 'Atkinson Hyperlegible Next', system-ui, sans-serif;",
         "  --vl-font-mono: 'JetBrains Mono', ui-monospace, monospace;",
         "  --vl-font-display: 'Pixelify Sans', monospace; /* wordmark, headlines; weight 700 */"]
        + [f"  --vl-display-{k}: {v['size']};" for k, v in t["font"]["display-scale"].items() if isinstance(v, dict)]
        + [f"  --vl-text-{k}: {v['size']}/{v['line']};" for k, v in sc.items() if isinstance(v, dict)]
        + [f"  --vl-space-{k}: {v};" for k, v in t["space"].items() if not k.startswith("$")]
        + ["  --vl-radius: 0px;", f"  --vl-elevation-1: {t['elevation']['1']};", f"  --vl-elevation-2: {t['elevation']['2']};",
           f"  --vl-glow: {t['elevation']['glow']};", "  --vl-duration: 200ms;", "  --vl-ease: cubic-bezier(.2,0,0,1);"])
    return ("/* Vectolyn design tokens, version 3. Light is the default; dark via system setting or data-theme. */\n"
            f":root {{\n{lines('light')}\n{other}\n  color-scheme: light dark;\n}}\n"
            "@media (prefers-color-scheme: dark) {\n  :root:not([data-theme=\"light\"]) {\n"
            + "\n".join("  " + l for l in lines("dark").splitlines()) + "\n  }\n}\n"
            f":root[data-theme=\"dark\"] {{\n{lines('dark')}\n}}\n")


def crop_footer(svgtxt, info, lines):
    """Crop a tab list to its footer area (the last n lines)."""
    L, R = info["panel"]
    y0 = info["bottom"] - lines * 9 - 4
    return re.sub(r'viewBox="[\d.\- ]+" width="\d+" height="\d+"',
                  f'viewBox="{L - 6} {y0} {R - L + 12} {lines * 9 + 8}" width="{R - L + 12}" height="{lines * 9 + 8}"',
                  svgtxt, count=1)


def main():
    os.makedirs(OUT, exist_ok=True)
    f = {}
    D, L = B.DARK, B.LIGHT
    for n, c in (("on-dark", D), ("on-light", L), ("mono-black", B.mono("#000000")), ("mono-white", B.mono("#FFFFFF"))):
        f[f"mark-{n}.svg"] = B.svg(64, 64, f'<g shape-rendering="crispEdges">{B.mark(c)}</g>', "Vectolyn mark")
    titles = {"product": "VectolynCloud", "net": "VECTOLYN.NET", "dotcloud": "VECTOLYN.CLOUD"}
    fname = {"product": "vectolyncloud", "net": "vectolyn-net", "dotcloud": "vectolyn-cloud-dot"}
    for mode, c in (("dark", D), ("light", L), ("mono-black", B.mono("#000000")), ("mono-white", B.mono("#FFFFFF"))):
        for kind in ("product", "net", "dotcloud"):
            body, w = B.lockup(c, 64, kind)
            f[f"lockup-{fname[kind]}-{mode}.svg"] = B.svg(math.ceil(w), 64, body, titles[kind])
    for mode, c in (("dark", D), ("light", L)):
        for kind in ("product", "net"):
            body, w = B.lockup(c, 32, kind, pixel=True)
            f[f"lockup-{fname[kind]}-32-{mode}.svg"] = B.svg(math.ceil(w), B.lockup_h(32, True), f'<g shape-rendering="crispEdges">{body}</g>', titles[kind])
        for kind in ("product", "net"):
            body, w = B.lockup(c, 64, kind, style="symbol")
            f[f"lockup-symbol-{fname[kind]}-{mode}.svg"] = B.svg(math.ceil(w), 64, body, titles[kind] + ", mark with full name")
        body, w, h = B.lockup_stacked(c, 96)
        f[f"lockup-stacked-{mode}.svg"] = B.svg(math.ceil(w), math.ceil(h), body, "VectolynCloud, stacked")
    f["server-icon-64.svg"] = B.svg(64, 64, B.server_icon64(), "Server icon")
    # Text layers of the banners (composited in export_v3.py)
    f["layers/social-text.svg"] = B.social_text()
    f["layers/release-text.svg"] = B.release_text()
    REL_CASES = [("stable", "v1.0.0", "Stage 2: cloud core"), ("dev", "v0.3.0-dev.2", "Stage 3: Bedrock tab list"),
                 ("alpha", "v1.1.0-alpha.1", "Stage 3: Bedrock tab list"), ("beta", "v1.1.0-beta.2", "Stage 3: Bedrock tab list"),
                 ("rc", "v1.1.0-rc.1", "Stage 3: Bedrock tab list"), ("lts", "v1.4.0-lts", "Long-term support"),
                 ("notitle", "v1.0.1", ""),
                 ("long", "v10.12.3-beta.12", "Stage 12: a much longer release title that needs to be shortened somewhere")]
    for name, v, ttl in REL_CASES:
        f[f"layers/release-text-{name}.svg"] = B.release_text(v, ttl, "2026-10-30")
    f["release-banner-template.svg"] = B.release_template()
    f["release-banner-spec.json"] = json.dumps(dict(layout=B.REL, badges=[dict(match=r, label=l, fill=c, text="#14111F") for r, l, c in B.BADGES],
                                                    versions=dict(tag="v<MAJOR>.<MINOR>.<PATCH>[-<suffix>], numbers without leading zeros, no +<build>",
                                                                  suffixes="-dev[.N], -alpha[.N], -beta[.N], -rc[.N], -lts (no counter), none = stable",
                                                                  below_1_0_0="-dev only",
                                                                  github_prerelease=["dev", "alpha", "beta", "rc"], github_release=["lts", "stable"],
                                                                  lts_fill="color.success (dark), 10.4:1 with #14111F",
                                                                  rules="tools/release-banner ReleaseVersion; anything else fails the release"),
                                                    font=dict(family="Pixelify Sans", weight=700, font_pixel_em=0.09143, tracking_em=-0.0286),
                                                    colors=dict(text="#ECEEF4", muted="#9EA3B8", point="#FFD23F"),
                                                    date_format="MMM d, yyyy (English)", background="branding/png/release-banner-background.png (+@2x)",
                                                    generator="tools/release-banner (Java, Gradle :tools:release-banner:releaseBanner); reference: branding/tools/brand3.py release_text(version, title, date_iso)"), indent=2, ensure_ascii=False)
    f["layers/readme-text-dark.svg"] = B.readme_text(D)
    f["layers/readme-text-light.svg"] = B.readme_text(L)

    # In game
    mc = {}
    mc["mc-serverlist-java.svg"] = U.server_list(K.entries(True), 2, True, "Multiplayer server list, Java 1.20.5+")
    mc["mc-serverlist-java18.svg"] = U.server_list(K.entries(False), 2, False, "Multiplayer server list, Java 1.8.9")
    bt = {B.C["cyan"]: "b", B.C["gold"]: "p", K.GRAY_D: "8", K.WHITE: "f", K.GOLD6: "p"}
    m1 = [(s[0], MM.BEDROCK[bt[s[1]]]) + tuple(s[2:]) for s in K.MOTD_BEDROCK]
    mc["mc-serverlist-bedrock.svg"] = U.bedrock_entry(m1)
    s, info = K.tab_original(True)
    mc["mc-tablist-tab-java.svg"] = s
    s, _ = K.tab_original(False)
    mc["mc-tablist-tab-java18.svg"] = s
    s, info = K.tab_brand(True, "time")
    mc["mc-tablist-vectolyn-java.svg"] = s
    s2, info2 = K.tab_brand(True, "date")
    mc["mc-tablist-vectolyn-java-date.svg"] = crop_footer(s2, info2, 9)
    s, _ = K.tab_brand(False)
    mc["mc-tablist-vectolyn-java18.svg"] = s
    mc["mc-tablist-vectolyn-bedrock-planned.svg"] = K.tab_bedrock()
    mc["mc-chat-version.svg"] = MM.chat(K.POWERED["version_en"])
    mc["mc-chat-version-de.svg"] = MM.chat(K.POWERED["version_de"])
    mc["mc-chat-version-bedrock.svg"] = MM.bedrock_chat([MM.bedrock_segs(l) for l in K.POWERED["version_en"]])
    f.update(mc)

    f["console-banner.txt"] = K.console_banner_txt()
    f["motd.txt"] = K.motd_txt()
    f["tab-vectolyn.yml"] = K.tab_yaml()
    t = tokens()
    f["tokens.json"] = json.dumps(t, ensure_ascii=False, indent=2) + "\n"
    f["tokens.css"] = tokens_css(t)
    for n, cnt in f.items():
        os.makedirs(os.path.dirname(f"{OUT}/{n}"), exist_ok=True)
        with open(f"{OUT}/{n}", "w", encoding="utf-8", newline="\n") as fh:
            fh.write(cnt)
    html = page3.page(f, mc, t, K.console_text())
    with open(f"{OUT}/index.html", "w", encoding="utf-8", newline="\n") as fh:
        fh.write(html)
    print("ok", len(f), "files")


if __name__ == "__main__":
    main()
