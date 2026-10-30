# Brand asset tools

Generators for everything in [`branding/`](..): vector assets, in-game mock-ups, text files, design tokens,
the brand guide page, the 3D renders and the PNG/video exports. All paths are derived from the location of
the scripts, so they run from any checkout. Nothing here touches the Gradle build.

## Requirements

| Step | Needs |
|---|---|
| `gen_v3.py` | Python (tested with 3.14) via [uv](https://docs.astral.sh/uv/) |
| `render_all.sh` | [Blender](https://www.blender.org/) 5.2 with Cycles |
| `export_v3.py` | Python via uv, Playwright with Chromium, ffmpeg (video only) |

Tested versions: uharfbuzz 0.57.0, fonttools 4.68.0, pillow 13.0.0, playwright 1.64.1, Blender 5.2, ffmpeg 9.x.
The fonts are read from [`../fonts`](../fonts) (Pixelify Sans, Atkinson Hyperlegible Next, JetBrains Mono, all
SIL OFL 1.1). Set `VECTOLYN_FONT_DIR` to read them from another folder with the same layout.

Install the browser once:

```sh
uv run --no-project --with playwright==1.64.1 python -m playwright install chromium
```

## Regenerate

Run all commands from this folder, in this order.

**1. Vectors, texts, tokens, guide page** (seconds, no GPU):

```sh
uv run --no-project --with uharfbuzz==0.57.0 --with fonttools==4.68.0 --with pillow==13.0.0 python gen_v3.py
```

Writes the SVGs, `layers/*.svg`, `motd.txt`, `tab-vectolyn.yml`, `console-banner.txt`, `tokens.json`,
`tokens.css`, `release-banner-template.svg`, `release-banner-spec.json` and `index.html` into `branding/`.
The output is deterministic: running it again must leave `git status` clean. Add `--out DIR` to write
somewhere else, for example to diff against the committed files.

**2. 3D renders** (minutes to hours, GPU):

```sh
BLENDER="/path/to/blender" sh render_all.sh
```

Renders the stills and the 240-frame turntable into `renders/` (git-ignored). `BLENDER` defaults to `blender`
from the `PATH`. The reference renders used Blender 5.2 with Cycles on an AMD GPU through HIP, which is the
default. For other hardware set `VECTOLYN_CYCLES_DEVICE` to `CUDA`, `OPTIX` (NVIDIA), `METAL` (Apple),
`ONEAPI` (Intel) or `CPU`. The CPU works but is much slower (hours). A missing GPU aborts the render instead
of falling back silently. Sample noise differs between devices, so renders are close to the committed ones
but not byte-identical.

**3. PNG, icons, video, page screenshots:**

```sh
uv run --no-project --with pillow==13.0.0 --with playwright==1.64.1 python export_v3.py
```

Composites the render with the vector text layers and writes `png/` and `video/` (H.264 MP4 and VP9 WebM via
ffmpeg). It deletes and recreates `png/` first. `--shots-only` skips the export and only screenshots the
guide page at 1280 px (dark) and 375 px (light) into `shots/` (git-ignored), reporting horizontal overflow,
console errors and broken images.

## What is generated where

| Script | Output |
|---|---|
| `gen_v3.py` | `branding/*.svg`, `layers/`, `*.txt`, `*.yml`, `*.json`, `tokens.css`, `index.html` |
| `render_all.sh` + `render_mark.py` | `renders/final/*.png`, `renders/tt/f_####.png` (not committed) |
| `export_v3.py` | `branding/png/`, `branding/video/` |

Module map: `core.py` (colour maths, text as outlined paths, the 8 x 8 mark), `brand3.py` (mark, wordmark,
lockups, banner text layers), `content3.py` (in-game content, MOTD, TAB, console texts), `mcfont.py`,
`mcmock.py`, `mcui.py` (pixel redraw of the Minecraft UI), `page3.py` (guide page), `render_mark.py`
(Blender scene).

`mcfont.py` is a hand-made pixel tracing of the Minecraft font. No Mojang files are included.

## Release banner

The banner of every release is produced in CI by the Java module `tools/release-banner`
(`./gradlew :tools:release-banner:releaseBanner`), not by these scripts. `release_text(version, title, date_iso)`
in `brand3.py` is the Python reference for its layout. [`../release-banner-spec.json`](../release-banner-spec.json)
and [`../release-banner-template.svg`](../release-banner-template.svg) describe the layout and slots, and
`../png/release-banner-background@2x.png` is the rendered background.

Badges follow the version rules of the Java module (`ReleaseVersion`, documented in
[docs/cloud/operations.md](../../docs/cloud/operations.md#versions-and-tags)): `-dev[.N]` PRE-RELEASE gray #9EA3B8,
`-alpha[.N]` ALPHA red #FF6B6B, `-beta[.N]` BETA gold #FFD23F, `-rc[.N]` RC cyan #00E5FF, `-lts` LTS green #5BD98A
(the dark `color.success` token, 10.4:1 with the obsidian badge text), no badge for a stable release. `BADGES` and
`release_badge` in `brand3.py` mirror these rules; a unit test of the Java module compares them with
`release-banner-spec.json`, so change both sides together.

The release examples in `../png/` (`release-example-*.png`, `release-banner-v0.2.0-dev*.png`) are normally written
by `export_v3.py`. When the 3D renders are not at hand, render them with the Java module over the committed
background, which gives the same picture as the CI release:
`java -cp tools/release-banner/build/classes/java/main net.vectolyn.tools.releasebanner.ReleaseBanner --font ... --name release-example-lts`
(run `./gradlew :tools:release-banner:classes` first; the options are listed in `ReleaseBanner.java`).
