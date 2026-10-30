# Branding

Brand assets for Vectolyn and VectolynCloud. Open [`index.html`](index.html) in a browser for the full brand
guide: mark, lockups, colors, type, in-game design, banners, voice and tone, and a download list.

| Path | Content |
|---|---|
| `index.html` | The brand guide (self-contained, no external requests) |
| `*.svg` | Mark, lockups, server icon, in-game mock-ups |
| `png/`, `video/` | Raster exports: banners, README headers, icons, key art, turntable |
| `layers/` | Text layers of the banners as outlined paths |
| `tokens.json`, `tokens.css` | Design tokens (colors, type, spacing, motion) |
| `motd.txt`, `tab-vectolyn.yml`, `console-banner.txt` | Ready-to-use in-game and console texts |
| `release-banner-template.svg`, `release-banner-spec.json` | Layout of the release banner |
| `fonts/` | Pixelify Sans, Atkinson Hyperlegible Next, JetBrains Mono (SIL OFL 1.1) |
| `tools/` | Generators, see [`tools/README.md`](tools/README.md) |

## Usage rules in brief

- The names are Vectolyn, VectolynCloud, VECTOLYN.NET and VECTOLYN.CLOUD. Write VectolynCloud as one word.
- Use the files as they are: do not recolor, stretch, rotate or add effects to the mark or the lockups.
  Pick the dark, light or mono variant that fits the background and keep the clear space.
- Colors are signal cyan `#00E5FF` and spawn gold `#FFD23F` on obsidian `#14111F`. The brand guide lists the
  light-mode values and contrast ratios.
- Where the mark is not available (plain text, chat, console), write the name out in full.

The guide has the full rules, including wrong uses.

The generators are parameterized: brand constants (palette, mark grid, URL, facts) live in `tools/brand.json`
(override with `VECTOLYN_BRAND`), so operators can later produce assets for their own network; this is planned for
the VectolynCloud dashboard.

## License

The assets are covered by the license of this repository ([GPL-3.0](../LICENSE)) unless stated otherwise.
The fonts in `fonts/` are licensed under the SIL Open Font License 1.1; each font folder contains its
`OFL.txt`.
