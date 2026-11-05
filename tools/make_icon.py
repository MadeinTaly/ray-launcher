#!/usr/bin/env python3
"""Draws the RAY icon, a manta gliding right across a low sun, from the curves below: launcher PNGs, TV banner, store images.
Needs Pillow. Run from the repo root: python tools/make_icon.py [preview.png]"""
import math
import sys
from PIL import Image, ImageDraw, ImageFilter

NIGHT_TOP, NIGHT_BOTTOM = (34, 16, 56), (9, 6, 22)
SUN_TOP, SUN_BOTTOM = (255, 176, 84), (236, 62, 58)
INK, RIM = (10, 6, 18), (255, 150, 74)

# Right half of the animal, from the middle of the head to the tip of the tail: cubic segments, unit = 1 % of the icon.
HALF = [
    ((0, -10.5), (1.6, -10.8), (3.2, -11.2), (3.9, -12.5)),   # front of the head, to the head fin
    ((3.9, -12.5), (3.2, -15.5), (2.6, -18.5), (1.6, -22)),   # inside of the fin, curling in at the tip
    ((1.6, -22), (5.8, -20), (8.6, -15.5), (7.8, -10.2)),     # outside of the fin
    ((7.8, -10.2), (16, -10.5), (30, -8), (46, -3.5)),        # leading edge of the wing
    ((46, -3.5), (33, -2.5), (23, 2), (16.5, 8)),             # trailing edge, hollow
    ((16.5, 8), (12, 12), (7, 15.5), (1.5, 16.5)),            # back to the root of the tail
    ((1.5, 16.5), (.9, 23), (.55, 31), (.05, 42)),            # tail, a thin whip
]
TILT, LEFT_WING, CENTER = math.radians(-17), .78, (49, 45)


def cubic(seg, n=28):
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = seg
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * t * (1 - t) ** 2, 3 * t * t * (1 - t), t ** 3
        yield a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3


def manta():
    """Outline in 0..100 icon units. The far wing is narrower: the animal banks instead of lying flat."""
    right = [p for seg in HALF for p in cubic(seg)]
    left = [(-x * LEFT_WING, y) for x, y in reversed(right)]
    out = []
    for x, y in right + left:
        y += .004 * x * x * (1 if x > 0 else .4)  # wings lifted at the tips
        if y > 17:
            x += .03 * (y - 17) ** 2  # the tail curls
        c, s = math.cos(TILT), math.sin(TILT)
        out.append((100 - (CENTER[0] + .84 * (x * c - y * s)), CENTER[1] + .84 * (x * s + y * c)))
    return out


def gradient(size, top, bottom):
    w, h = size
    column = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return column.resize((w, h))


def art(size, foreground_only=False, x0=0, scale=None):
    """The picture at size (w, h). The animal keeps a square of side `scale` starting at x0."""
    ss = 4
    w, h = size[0] * ss, size[1] * ss
    u = (scale or size[0]) * ss / 100
    ox = x0 * ss
    sky = Image.new("RGBA", (w, h), (0, 0, 0, 0)) if foreground_only else gradient((w, h), NIGHT_TOP, NIGHT_BOTTOM).convert("RGBA")
    if not foreground_only:
        cx, cy, r = ox + 50 * u, 47 * u, 29 * u
        sun = gradient((w, h), SUN_TOP, SUN_BOTTOM).convert("RGBA")
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
        sky.paste(sun, (0, 0), mask)
    points = [(ox + x * u, y * u) for x, y in manta()]
    d = ImageDraw.Draw(sky)
    rim = Image.new("L", (w, h), 0)
    ImageDraw.Draw(rim).polygon(points, fill=255)
    rim = rim.filter(ImageFilter.MaxFilter(2 * max(1, round(.42 * u)) + 1))
    sky.paste(RIM + (255,), (0, 0), rim)
    d.polygon(points, fill=INK + (255,))
    return sky.resize(size, Image.LANCZOS)


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def circle(img):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.size[0] - 1, img.size[1] - 1), fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    if len(sys.argv) > 1:
        sheet = Image.new("RGB", (900, 540), (40, 40, 44))
        sheet.paste(art((512, 512)).convert("RGB"), (10, 10))
        y = 10
        for s in (192, 96, 48, 32):
            sheet.paste(circle(art((s, s))), (532, y), circle(art((s, s))))
            y += s + 10
        banner = art((320, 180), x0=70, scale=180)
        sheet.paste(banner.convert("RGB"), (560 + 96, 10))
        sheet.save(sys.argv[1])
        return
    for name, launcher in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = f"app/res/mipmap-{name}/"
        full = art((launcher, launcher))
        rounded(full, round(launcher * .2)).save(d + "ic_launcher.png")
        circle(full).save(d + "ic_launcher_round.png")
        adaptive = launcher * 108 // 48
        # adaptive layers: only the central two thirds is sure to show, so the picture is drawn inside it on its own sky
        layer = gradient((adaptive, adaptive), NIGHT_TOP, NIGHT_BOTTOM).convert("RGBA")
        layer.save(d + "ic_launcher_background.png")
        inset = (adaptive - adaptive * 72 // 108) // 2
        fore = Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0))
        part = art((adaptive * 72 // 108, adaptive * 72 // 108))
        fore.paste(part, (inset, inset))
        fore.save(d + "ic_launcher_foreground.png")
    art((320, 180), x0=70, scale=180).convert("RGB").save("app/res/drawable-xhdpi/ray_banner.png")
    images = "fastlane/metadata/android/en-US/images/"
    art((512, 512)).convert("RGB").save(images + "icon.png")
    art((1280, 720), x0=280, scale=720).convert("RGB").save(images + "tvBanner.png")
    art((1024, 500), x0=262, scale=500).convert("RGB").save(images + "featureGraphic.png")


if __name__ == "__main__":
    main()
