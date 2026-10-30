# Fonts

The three typefaces of the Vectolyn brand, all under the [SIL Open Font License 1.1](https://openfontlicense.org) (the `OFL.txt` next to each font). None of them declares a Reserved Font Name.

| File | Use |
|---|---|
| `pixelify-sans/PixelifySans-Variable.ttf` | Pixelify Sans, weight axis 400 to 700: wordmark, headlines, release banner |
| `pixelify-sans/PixelifySans-Bold.ttf` | static weight 700 instance of the file above, for tools that cannot select a variable instance (Java2D in [`tools/release-banner`](../../tools/release-banner)) |
| `atkinson-hyperlegible-next/AtkinsonHyperlegibleNext-Variable.ttf` | Atkinson Hyperlegible Next, weight axis 200 to 800: body text |
| `jetbrains-mono/JetBrainsMono-Variable.ttf` | JetBrains Mono, weight axis 100 to 800: code, commands, versions in running text |

## Sources

All variable fonts and license texts come unchanged from [google/fonts](https://github.com/google/fonts), each at the last commit that touched its folder:

| Folder in google/fonts | Commit | Upstream project |
|---|---|---|
| [`ofl/pixelifysans`](https://github.com/google/fonts/tree/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/pixelifysans) | `8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5` | [eifetx/Pixelify-Sans](https://github.com/eifetx/Pixelify-Sans) at `39df74aba80df8157546034b878e8be1eb565ced` |
| [`ofl/atkinsonhyperlegiblenext`](https://github.com/google/fonts/tree/95f4904fc8bcf26d3420fe315560c96417c6dec7/ofl/atkinsonhyperlegiblenext) | `95f4904fc8bcf26d3420fe315560c96417c6dec7` | [googlefonts/atkinson-hyperlegible-next](https://github.com/googlefonts/atkinson-hyperlegible-next) |
| [`ofl/jetbrainsmono`](https://github.com/google/fonts/tree/6e4b84c976cadb3c49a40fd9a1c203e4f7fcf2da/ofl/jetbrainsmono) | `6e4b84c976cadb3c49a40fd9a1c203e4f7fcf2da` | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) |

The files were renamed: `PixelifySans[wght].ttf` to `PixelifySans-Variable.ttf`, `AtkinsonHyperlegibleNext[wght].ttf` to `AtkinsonHyperlegibleNext-Variable.ttf`, `JetBrainsMono[wght].ttf` to `JetBrainsMono-Variable.ttf`. `PixelifySans[wght].ttf` is byte for byte the same file as `fonts/variable/PixelifySans[wght].ttf` in the upstream repository at the commit above, and the same as the file the brand set was designed with.

## SHA-256

| File | SHA-256 |
|---|---|
| `pixelify-sans/PixelifySans-Variable.ttf` | `9ba86cd010a4de309d263ceff8e8044092c9db7efda869620cb9ff1c4389e8a5` |
| `pixelify-sans/PixelifySans-Bold.ttf` | `5cb117ef27acac7427f85353a029a4e3d61095aa6a72fe4585b9b5d052b8f5c5` |
| `pixelify-sans/OFL.txt` | `921ba087e40693958a9b2519d489b8872a894a6f5b1a9bfae37639146c1effe9` (LF line ends; the upstream file has CRLF and `b66ba46f511a851ab09998b5a5a9fdbb102545a3864cb993095e1745996873a7`) |
| `atkinson-hyperlegible-next/AtkinsonHyperlegibleNext-Variable.ttf` | `5a455d1cfa099b601ab70751bb9673e8fe1854dc4500c80e1a220d0d75e31745` |
| `atkinson-hyperlegible-next/OFL.txt` | `aca6a428580965d2297d1b718042dd427c2a9443ece3b0d02d758e161e0c4030` |
| `jetbrains-mono/JetBrainsMono-Variable.ttf` | `48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda` |
| `jetbrains-mono/OFL.txt` | `b2fe5e8987594e9ffd1d2ca52a2f5d73eb8335243893c5d6254b5ad69269591d` |

Check them with `sha256sum` from this folder, for example `sha256sum pixelify-sans/*.ttf`.

## How the static Bold was made

The upstream repository also ships a static `fonts/ttf/PixelifySans-Bold.ttf`, but it is not the same design as the variable font at weight 700: twelve glyphs differ in shape (`)`, `{`, `Ø`, `Ǿ`, `ø`, `ǿ`, `ч`, `≤`, `≥`, `≠`, `¼`, `ð`), and it carries ttfautohint hinting. The brand set and its generators use the variable font at 700, so the static file here is that instance, made with fontTools 4.68.0:

```sh
uv run --no-project --with fonttools==4.68.0 python -I instance.py PixelifySans-Variable.ttf PixelifySans-Bold.ttf
```

with `instance.py`:

```python
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

font = TTFont(sys.argv[1], recalcTimestamp=False)
instantiateVariableFont(font, {"wght": 700}, updateFontNames=True).save(sys.argv[2])
```

`recalcTimestamp=False` keeps the result reproducible: running it again gives the same SHA-256. The instance is named Pixelify Sans Bold (style name from the font's STAT table), as the OFL allows for a font without a Reserved Font Name; its copyright and license entries are those of the variable font. Its outlines equal the variable font at 700 apart from rounding to whole font units, its kerning is the variable font's kerning at 700, and its character set is the same (574 characters: Latin, Latin Extended and part of Cyrillic; there is no `П`, for example).
