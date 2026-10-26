#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

def letterbox(img: Image.Image) -> np.ndarray:
    w, h = img.size
    s = min(256 / w, 256 / h)
    nw, nh = w * s, h * s
    ox, oy = (256 - nw) / 2, (256 - nh) / 2
    canvas = Image.new("RGB", (256, 256), (0, 0, 0))
    resized = img.resize((max(1, round(nw)), max(1, round(nh))), Image.BILINEAR)
    canvas.paste(resized, (round(ox), round(oy)))
    return np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0

def main() -> None:
    model, dataset, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    session = ort.InferenceSession(str(model))
    index = []
    for label_path in sorted((dataset / "labels").glob("*.json")):
        label = json.loads(label_path.read_text())
        img = Image.open(dataset / "images" / label["image"]).convert("RGB")
        heatmaps, mask = session.run(["corner_heatmaps", "mask_logits"], {"input": letterbox(img)})
        name = label_path.stem
        np.concatenate([heatmaps.ravel(), mask.ravel()]).astype("<f4").tofile(out / f"{name}.bin")
        index.append({"name": name, "width": img.size[0], "height": img.size[1], "corners": label["corners_px"]})
    (out / "index.json").write_text(json.dumps(index, indent=1))
    print(f"{len(index)} images -> {out}")

if __name__ == "__main__":
    main()
