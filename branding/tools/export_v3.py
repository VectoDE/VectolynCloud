"""Exports banners (render + vector type), icons, mock-up PNGs, the video and page screenshots.
Run from branding/tools/ (see README.md):
    uv run --no-project --with pillow==13.0.0 --with playwright==1.64.1 python export_v3.py [--shots-only]
Needs the Blender renders in <repo>/branding/tools/renders/ (render_all.sh) and ffmpeg on the PATH for the video.
Writes png/ and video/ into branding/; scratch files go to branding/tools/renders and branding/tools/tmp (git-ignored)."""
import pathlib
import re
import shutil
import subprocess
import sys

from PIL import Image
from playwright.sync_api import sync_playwright

TOOLS = pathlib.Path(__file__).resolve().parent
FIN = TOOLS.parent  # branding/
REN = TOOLS / "renders"  # Blender output (render_all.sh)
WORK = TOOLS / "tmp"  # scratch files
PNG, VID, SHOTS = FIN / "png", FIN / "video", TOOLS / "shots"
VERSION = ("0.2.0", "First light", "2026-10-30")


def comp_html(w, h, layers):
    body = "".join(f'<img src="{u}" style="position:absolute;inset:0;width:{w}px;height:{h}px">' for u in layers)
    return (f'<!doctype html><html><body style="margin:0;background:transparent">'
            f'<div style="position:relative;width:{w}px;height:{h}px;overflow:hidden">{body}</div></body></html>')


def shoot(page, html, w, h, out, scale_page=None):
    WORK.mkdir(exist_ok=True)
    tmp = WORK / "_tmp.html"
    tmp.write_text(html, encoding="utf-8")
    page.set_viewport_size({"width": w, "height": h})
    page.goto(tmp.as_uri())
    page.wait_for_load_state("networkidle")
    page.screenshot(path=str(out), omit_background=True, clip={"x": 0, "y": 0, "width": w, "height": h})
    tmp.unlink()


def svg_size(p):
    m = re.search(r'viewBox="[\d.\-]+ [\d.\-]+ ([\d.]+) ([\d.]+)"', p.read_text(encoding="utf-8"))
    return float(m.group(1)), float(m.group(2))


def flat(src, dst, bg):
    im = Image.open(src).convert("RGBA")
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    b.convert("RGB").save(dst)
    return dst


OBS = (0x14, 0x11, 0x1F)


def lift(src, dst):
    """Lighten blend with obsidian: the deep black of the render becomes #14111F, the light stays."""
    im = Image.open(src).convert("RGB")
    from PIL import ImageChops
    ImageChops.lighter(im, Image.new("RGB", im.size, OBS)).save(dst)
    return dst


def assets(pw):
    if PNG.exists():
        shutil.rmtree(PNG)
    PNG.mkdir(parents=True)
    VID.mkdir(exist_ok=True)
    tmpd = WORK
    tmpd.mkdir(exist_ok=True)
    r = REN / "final"
    light_bg = flat(r / "readme_light.png", tmpd / "readme_light_flat.png", "#F3F5F7")
    for j in ("social", "release", "readme_dark", "hero"):
        lift(r / f"{j}.png", tmpd / f"{j}_obs.png")
    rb = Image.open(tmpd / "release_obs.png")
    rb.save(PNG / "release-banner-background@2x.png")
    rb.resize((1280, 320), Image.LANCZOS).save(PNG / "release-banner-background.png")
    jobs = [("social-preview", 1280, 640, [tmpd / "social_obs.png", FIN / "layers/social-text.svg"]),
            (f"release-banner-v{VERSION[0]}", 1280, 320, [tmpd / "release_obs.png", FIN / "layers/release-text.svg"]),
            ("readme-header-dark", 1280, 280, [tmpd / "readme_dark_obs.png", FIN / "layers/readme-text-dark.svg"]),
            ("readme-header-light", 1280, 280, [light_bg, FIN / "layers/readme-text-light.svg"])]
    b = pw.chromium.launch()
    for k in (1, 2):
        page = b.new_page(device_scale_factor=k)
        for name, w, h, lay in jobs:
            shoot(page, comp_html(w, h, [p.as_uri() for p in lay]), w, h, PNG / f"{name}{'@2x' if k == 2 else ''}.png")
        page.close()
    page = b.new_page(device_scale_factor=1)
    for case in ("stable", "alpha", "beta", "rc", "pre", "notitle", "long"):
        shoot(page, comp_html(1280, 320, [(tmpd / "release_obs.png").as_uri(), (FIN / f"layers/release-text-{case}.svg").as_uri()]),
              1280, 320, PNG / f"release-example-{case}.png")
    page.close()
    # Pixel-exact icons: SVG with crispEdges at the exact size
    page = b.new_page(device_scale_factor=1)
    for name, src, sz in (("server-icon", "server-icon-64.svg", 64), ("favicon-16", "mark-on-dark.svg", 16),
                          ("favicon-32", "mark-on-dark.svg", 32), ("favicon-48", "mark-on-dark.svg", 48),
                          ("apple-touch-icon", "mark-on-dark.svg", 180)):
        u = (FIN / src).as_uri()
        bg = "transparent" if name == "server-icon" else "#14111F"
        pad = {"favicon-16": 0, "favicon-32": 4, "favicon-48": 8, "apple-touch-icon": 26}.get(name, 0)  # whole cell sizes: 2, 3, 4, 16 px
        html = (f'<!doctype html><body style="margin:0"><div style="width:{sz}px;height:{sz}px;background:{bg};display:grid;place-items:center">'
                f'<img src="{u}" style="width:{sz - 2 * pad}px;height:{sz - 2 * pad}px;image-rendering:pixelated"></div></body>')
        shoot(page, html, sz, sz, PNG / f"{name}.png")
    # In-game views as PNG, scaled by whole numbers (GUI scale 2) with pixelated
    for svg in sorted(FIN.glob("mc-*.svg")):
        w, h = svg_size(svg)
        for k in (2, 4):
            html = (f'<!doctype html><body style="margin:0"><img src="{svg.as_uri()}" width="{int(w * k)}" height="{int(h * k)}" '
                    f'style="display:block;image-rendering:pixelated"></body>')
            shoot(page, html, int(w * k), int(h * k), PNG / f"{svg.stem}{'@2x' if k == 4 else ''}.png")
    page.close()
    b.close()
    # 3D: key art 8 bit, transparent mark
    hero = Image.open(tmpd / "hero_obs.png").convert("RGB")
    hero.save(PNG / "keyart-3840x2160.png", optimize=True)
    hero.save(PNG / "keyart-3840x2160.jpg", quality=90, optimize=True, progressive=True)
    hero.resize((1920, 1080), Image.LANCZOS).save(PNG / "keyart-1920x1080.jpg", quality=88, optimize=True, progressive=True)
    Image.open(r / "mark_alpha.png").convert("RGBA").save(PNG / "mark-3d-2048.png", optimize=True)
    Image.open(r / "mark_alpha.png").convert("RGBA").resize((512, 512), Image.LANCZOS).save(PNG / "mark-3d-512.png", optimize=True)
    # Turntable
    frames = str(REN / "tt" / "f_%04d.png")
    if (REN / "tt" / "f_0240.png").exists():
        lift_f = ["-vf", "format=rgb24,lutrgb=r='max(val,20)':g='max(val,17)':b='max(val,31)'"]
        ff = ["ffmpeg", "-y", "-loglevel", "error", "-framerate", "30", "-start_number", "1", "-i", frames] + lift_f
        subprocess.run(ff + ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "slow", "-movflags", "+faststart",
                             str(VID / "mark-turntable.mp4")], check=True)
        subprocess.run(ff + ["-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-crf", "30", "-b:v", "0", "-row-mt", "1",
                             str(VID / "mark-turntable.webm")], check=True)
    else:
        print("WARNING: turntable frames incomplete, video skipped")


def shots(pw):
    SHOTS.mkdir(exist_ok=True)
    b = pw.chromium.launch()
    res = []
    for w, scheme, name in ((1280, "dark", "page-1280-dark.png"), (375, "light", "page-375-light.png")):
        ctx = b.new_context(viewport={"width": w, "height": 900}, color_scheme=scheme, reduced_motion="reduce")
        page = ctx.new_page()
        errs = []
        page.on("console", lambda m, e=errs: e.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda x, e=errs: e.append(str(x)))
        page.goto((FIN / "index.html").as_uri())
        page.wait_for_load_state("networkidle")
        page.evaluate("document.querySelectorAll('img[loading=lazy]').forEach(i=>i.loading='eager')")
        page.wait_for_timeout(800)
        sw = page.evaluate("[document.documentElement.scrollWidth, document.documentElement.clientWidth]")
        bad = page.evaluate("[...document.images].filter(i=>!i.complete||i.naturalWidth===0).map(i=>i.getAttribute('src'))")
        page.screenshot(path=str(SHOTS / name), full_page=True)
        page.screenshot(path=str(SHOTS / name.replace(".png", "-top.png")))
        res.append((name, sw, errs, bad))
        ctx.close()
    b.close()
    for name, sw, errs, bad in res:
        print(f"{name}: scrollWidth={sw[0]} clientWidth={sw[1]} errors={errs} broken_images={bad}")


if __name__ == "__main__":
    with sync_playwright() as pw:
        if "--shots-only" not in sys.argv:
            assets(pw)
        shots(pw)
