# Derived from https://github.com/irvan-putra/simak/blob/main/tools/badge_icon.py
# Shared adaptive-aware family geometry; only kapan art/palette differ.
#!/usr/bin/env python3
"""Launcher icon generator: one picture plus the shared version badge.

Deterministic. Same version in, same PNGs out. Inputs are pinned: Pillow
PILLOW_PIN and DejaVu Sans Bold with sha256 FONT_SHA256. The script fails
instead of falling back to another font or Pillow version, because a
different rasterizer or font changes the pixels.
    pip install pillow==12.3.0   (the font is vendored in tools/fonts/)

Badge: numbers only, a full-width strip across the bottom 24% of the icon.
Picture safety: every picture pixel is checked to lie inside the circle that
touches the icon edges (shrunk by a margin), so round launcher masks do not
clip it; the version text is checked the same way.

One icon variant only, Irvan's call 2026-11-23 ("give me most detailed one
as the only one we use"): a single res/drawable/ic_launcher.png at
xxxhdpi-class 192px - the rounded-square icon with the strip. Launchers
downscale it per density themselves. No mipmap ladder, no adaptive-icon
XML and no themed (monochrome) layer: an adaptive/monochrome layer lets
launchers with themed/palette icons enabled (Irvan's Samsung does) replace
the art with a flat palette glyph, hiding the picture.
Family icons must always show the full-colour art.

`--check` regenerates everything into a temp dir and fails if any
committed resource differs, so CI can prove the PNGs came from this
script.
"""
import hashlib, math, os, re, shutil, sys, tempfile
import PIL
from PIL import Image, ImageChops, ImageDraw, ImageFont

PILLOW_PIN = "12.3.0"
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FONT_VENDORED = os.path.join(ROOT, "tools", "fonts", "DejaVuSans-Bold.ttf")
FONT_SYSTEM = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_SHA256 = "d1c3ff99f1e1ce1827a33efd4dad81f40babda06bff9e43bd7591c86662a287b"
ICON_PX = 192  # xxxhdpi-class: the single shipped variant
S = 768
STRIP_TOP = 0.76      # strip covers 76%..100% of the icon height
TEXT_SIZE = 0.115     # numeral size, fraction of icon size
SAFE_R = 0.485        # picture and text must stay inside this radius (fraction)

if PIL.__version__ != PILLOW_PIN:
    sys.exit("Pillow %s required, found %s" % (PILLOW_PIN, PIL.__version__))


def font_path():
    # Prefer the vendored, hash-checked font (identical on every machine);
    # fall back to the system install for local runs.
    for path in (FONT_VENDORED, FONT_SYSTEM):
        if os.path.exists(path):
            return path
    sys.exit("missing font: neither " + FONT_VENDORED + " nor " + FONT_SYSTEM)


def font(px):
    path = font_path()
    if hashlib.sha256(open(path, "rb").read()).hexdigest() != FONT_SHA256:
        sys.exit("unexpected font file " + path)
    return ImageFont.truetype(path, px)


def circle_mask(size, r=SAFE_R):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([size * (.5 - r), size * (.5 - r), size * (.5 + r), size * (.5 + r)], fill=255)
    return m


def assert_inside_circle(layer, what, size=S, r=SAFE_R):
    alpha = layer.getchannel("A").point(lambda v: 255 if v > 8 else 0)
    outside = ImageChops.multiply(alpha, ImageChops.invert(circle_mask(size, r)))
    box = outside.getbbox()
    if box:
        sys.exit("%s leaves the round-mask safe circle at %s" % (what, box))


def new_layer(size=S):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def compose(bg, art, version, strip, text, rounded=None, size=S, circle_check=True):
    """Background, then the picture layer, then the badge strip."""
    if circle_check:
        assert_inside_circle(art, "picture", size)
    base = Image.new("RGBA", (size, size), bg) if bg else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base.alpha_composite(art)
    d = ImageDraw.Draw(base)
    y0 = int(size * STRIP_TOP)
    d.rectangle([0, y0, size, size], fill=strip)
    tl, td = new_layer(size)
    f = font(int(size * TEXT_SIZE))
    td.text((size / 2, (y0 + size) / 2), version, font=f, fill=text, anchor="mm")
    if circle_check:
        assert_inside_circle(tl, "version text", size)
    base.alpha_composite(tl)
    if rounded:
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=rounded * size, fill=255)
        base.putalpha(ImageChops.multiply(base.getchannel("A"), m))
    return base


def parse_version(argv, fallback=None):
    v = argv[1] if len(argv) > 1 else fallback
    if not v or not re.fullmatch(r"\d+\.\d+\.\d+", v):
        sys.exit("version must be X.Y.Z")
    return v


KAPAN_BG = (14, 15, 19, 255)        # site background ink
KAPAN_CARD = (232, 234, 240, 255)   # paper
KAPAN_LINE = (126, 224, 163, 255)   # site accent green
KAPAN_FILM = (126, 195, 224, 255)   # site accent blue
KAPAN_STRIP = (10, 11, 14, 255)


def kapan_art():
    """A calendar page (kapan = when) with a small film clapperboard corner -
    the two audiences on the site."""
    art, d = new_layer()
    cx, cy = S * .47, S * .50
    w, h = S * .46, S * .42
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    # calendar card
    d.rounded_rectangle([x0, y0, x1, y1], radius=S * .03, fill=KAPAN_CARD)
    # header band with two binding rings
    band = y0 + h * .24
    d.rounded_rectangle([x0, y0, x1, band], radius=S * .03, fill=KAPAN_LINE)
    d.rectangle([x0, band - h * .06, x1, band], fill=KAPAN_LINE)
    for rx in (cx - w * .25, cx + w * .25):
        d.rounded_rectangle([rx - S * .012, y0 - S * .028, rx + S * .012, y0 + h * .10],
                            radius=S * .012, fill=KAPAN_CARD)
    # day grid: 3 x 4 dots punched in ink, one marked in film blue
    cols, rows = 4, 3
    gx0, gy0 = x0 + w * .14, band + h * .14
    gdx, gdy = w * .24, h * .20
    r = S * .026
    mark = (2, 1)
    for row in range(rows):
        for col in range(cols):
            px, py = gx0 + col * gdx, gy0 + row * gdy
            fill = KAPAN_FILM if (col, row) == mark else KAPAN_BG
            d.ellipse([px - r, py - r, px + r, py + r], fill=fill)
    # clapperboard leaning on the bottom-right corner
    bx, by = x1 - S * .01, y1 - S * .10
    bw, bh = S * .17, S * .13
    d.rounded_rectangle([bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2],
                        radius=S * .015, fill=KAPAN_BG)
    # slanted stripes on the clapper top
    for i in range(4):
        sx = bx - bw / 2 + i * (bw / 4)
        d.polygon([(sx, by - bh / 2), (sx + bw / 8, by - bh / 2),
                   (sx + bw / 8 - S * .012, by - bh * .20), (sx - S * .012, by - bh * .20)],
                  fill=KAPAN_CARD)
    return art


def kapan(version):
    return compose(KAPAN_BG, kapan_art(), version, KAPAN_STRIP, KAPAN_CARD, rounded=.2)


def write_all(version, res_dir):
    drawables = os.path.join(res_dir, "drawable")
    os.makedirs(drawables, exist_ok=True)
    kapan(version).resize((ICON_PX, ICON_PX), Image.LANCZOS).save(
        os.path.join(drawables, "ic_launcher.png"), optimize=True)


def written_paths(res_dir):
    return [os.path.join(res_dir, "drawable", "ic_launcher.png")]


def check(version, res_dir):
    """Fail unless every committed resource matches this script's output."""
    tmp = tempfile.mkdtemp(prefix="kapan-icons-")
    try:
        write_all(version, tmp)
        bad = []
        for generated in written_paths(tmp):
            committed = os.path.join(res_dir, os.path.relpath(generated, tmp))
            if not os.path.exists(committed) or open(committed, "rb").read() != open(generated, "rb").read():
                bad.append(os.path.relpath(committed, res_dir))
        if bad:
            sys.exit("committed launcher resources differ from the generator: " + ", ".join(bad))
        print("launcher resources match the generator for", version)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def default_version():
    with open(os.path.join(ROOT, "app", "build.gradle.kts")) as fh:
        text = fh.read()
    for pattern in (r'versionName\s*=\s*"([^"]+)"', r'appVersionName\s*=\s*"([^"]+)"'):
        m = re.search(pattern, text)
        if m:
            return m.group(1)
    sys.exit("could not read the app version from app/build.gradle.kts")


def main():
    check_mode = "--check" in sys.argv[1:]
    argv = [a for a in sys.argv[1:] if a != "--check"]
    version = parse_version(["badge_icon.py"] + argv, fallback=default_version())
    res_dir = os.path.join(ROOT, "app", "src", "main", "res")
    if check_mode:
        check(version, res_dir)
    else:
        write_all(version, res_dir)
        print("wrote icons for", version)


if __name__ == "__main__":
    main()
