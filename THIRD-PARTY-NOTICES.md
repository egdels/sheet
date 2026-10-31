# Third-party notices

Sheet is GPL-3.0-or-later. It ships or builds on:

- **ONNX Runtime** 1.24.1 (MIT), built from source from the
  `external/onnxruntime` submodule by `scripts/build_onnxruntime.sh`.
  Copyright (c) Microsoft Corporation.
- **PaddleOCR PP-OCRv5 and PP-OCRv6** detection and recognition models with their
  dictionaries (Apache-2.0), converted to ONNX Runtime's format, in
  `app/src/main/assets/paddleocr`. Copyright (c) PaddlePaddle Authors.
  Licence text and details in `third_party_licenses/paddleocr`.
- **DocQuadNet-256**, the corner model with its trained weights in
  `app/src/main/assets/docquad` (Apache-2.0, not GPL). Copyright (c) 2026
  Christian Kierdorf; trained with MakeACopy's training code on UVDoc
  (MIT), SmartDoc 2015 (CC BY 4.0), receipts from CORD (NAVER Clova AI,
  CC BY 4.0) and DTD textures as backgrounds (no explicit licence); none
  of the datasets is redistributed. Licence text, sources and dataset
  credits in `third_party_licenses/docquadnet`.
- AndroidX, Jetpack Compose, CameraX, Material icons and Kotlin
  coroutines/serialization (Apache-2.0), from Google's Maven repository
  and Maven Central.
