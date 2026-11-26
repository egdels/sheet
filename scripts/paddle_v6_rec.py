#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

import hashlib
import subprocess
import sys
from pathlib import Path

import yaml

NAME = "PP-OCRv6_small_rec"
ONNX_SHA256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"

def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    onnx = src / "inference.onnx"
    digest = hashlib.sha256(onnx.read_bytes()).hexdigest()
    if digest != ONNX_SHA256:
        sys.exit(f"{onnx}: sha256 {digest}, expected {ONNX_SHA256}")
    chars = yaml.safe_load((src / "inference.yml").read_text(encoding="utf-8"))["PostProcess"]["character_dict"]
    (out / f"{NAME}_dict.txt").write_text("\n".join(chars) + "\n \n", encoding="utf-8")
    subprocess.check_call([str(Path(__file__).with_name("models_to_ncnn.sh")), "rec", str(onnx), str(out), NAME])

if __name__ == "__main__":
    main()
