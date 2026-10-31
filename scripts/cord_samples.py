#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

import json
import math
import sys
from pathlib import Path

from PIL import Image

def main() -> None:
    split, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for js in sorted((split / "json").glob("*.json")):
        photo = split / "image" / f"{js.stem}.png"
        if not photo.is_file():
            continue
        label = json.loads(js.read_text(encoding="utf-8"))
        img = Image.open(photo).convert("RGB")
        meta = label["meta"]["image_size"]
        sx, sy = img.width / meta["width"], img.height / meta["height"]
        roi = label["roi"]
        if roi:
            corners = [(roi[f"x{i}"] * sx, roi[f"y{i}"] * sy) for i in range(1, 5)]
        else:
            corners = [(0, 0), (img.width, 0), (img.width, img.height), (0, img.height)]
        width = round(max(math.dist(corners[0], corners[1]), math.dist(corners[3], corners[2])))
        height = round(max(math.dist(corners[0], corners[3]), math.dist(corners[1], corners[2])))
        tl, tr, br, bl = corners
        page = img.transform((width, height), Image.QUAD, (*tl, *bl, *br, *tr), resample=Image.BICUBIC, fillcolor=(255, 255, 255))
        rows: dict[int, list[tuple[float, float, str]]] = {}
        for line in label["valid_line"]:
            for word in line["words"]:
                q = word["quad"]
                rows.setdefault(word["row_id"], []).append(((q["x1"] + q["x4"]) / 2, (q["y1"] + q["y4"]) / 2, word["text"]))
        ordered = sorted(rows.values(), key=lambda ws: sum(w[1] for w in ws) / len(ws))
        truth = "\n".join(" ".join(w[2] for w in sorted(ws)) for ws in ordered)
        page.save(out / f"cord_{js.stem}.png")
        (out / f"cord_{js.stem}.gt.txt").write_text(truth + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
