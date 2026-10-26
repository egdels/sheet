#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BACKGROUND = "#1E3A4C"
VISIBLE = 72
SHEET = [(41, 35), (60, 35), (67, 42), (67, 73), (41, 73)]
FOLD = [(60, 35), (60, 42), (67, 42)]
TEXT = [(45, 49, 63, 51.2), (45, 55, 63, 57.2), (45, 61, 63, 63.2), (45, 67, 56, 69.2)]
FRAME = [[(35, 36), (35, 30), (41, 30)], [(67, 30), (73, 30), (73, 36)], [(73, 72), (73, 78), (67, 78)], [(41, 78), (35, 78), (35, 72)]]

def draw(size: int) -> Image.Image:
    ss = 4
    s = size * ss / VISIBLE
    o = (108 - VISIBLE) / 2
    img = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size * ss - 1, size * ss - 1], radius=int(size * ss * 0.22), fill=BACKGROUND)
    pts = lambda ps: [((x - o) * s, (y - o) * s) for x, y in ps]
    d.polygon(pts(SHEET), fill="#FFFFFF")
    d.polygon(pts(FOLD), fill="#B0BEC5")
    for x0, y0, x1, y1 in TEXT:
        d.rectangle([(x0 - o) * s, (y0 - o) * s, (x1 - o) * s, (y1 - o) * s], fill="#78909C")
    w = int(3.2 * s)
    for line in FRAME:
        d.line(pts(line), fill="#00C853", width=w, joint="curve")
        for x, y in pts([line[0], line[-1]]):
            d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill="#00C853")
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    out = ROOT / "fastlane/metadata/android/en-US/images"
    out.mkdir(parents=True, exist_ok=True)
    draw(512).save(out / "icon.png")
    (ROOT / "Packaging").mkdir(exist_ok=True)
    draw(256).save(ROOT / "Packaging/AppIcon-256.png")
    print("wrote", out / "icon.png", "and Packaging/AppIcon-256.png")
