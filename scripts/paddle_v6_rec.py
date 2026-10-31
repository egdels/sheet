#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import onnxruntime as ort
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
    with tempfile.TemporaryDirectory() as tmp:
        stage = Path(tmp) / "stage"
        stage.mkdir()
        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        options.optimized_model_filepath = str(stage / f"{NAME}.onnx")
        ort.InferenceSession(str(onnx), options, providers=["CPUExecutionProvider"])
        subprocess.check_call(
            [sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", str(stage),
             "--optimization_style", "Fixed", "--target_platform", "arm"],
            cwd=tmp,
        )
        shutil.copy2(stage / f"{NAME}.ort", out / f"{NAME}.ort")

if __name__ == "__main__":
    main()
