# DocQuadNet-256

The corner model in `app/src/main/assets/docquad/docquadnet256.ncnn.param`
(the layers) and `docquadnet256.ncnn.bin` (the weights):
MobileNetV3-Large with a small feature pyramid, 256 x 256 input, four
corner heatmaps and a page mask. It carries the trained weights; it was
trained from scratch (no pretrained backbone weights).

Copyright (C) 2026 Christian Kierdorf. Licensed under the Apache License,
Version 2.0 (`LICENSE-Apache-2.0.txt` in this directory), separately from
the GPL-3.0-or-later that covers Sheet's code.

- Files: NCNN format, single precision, converted from the ONNX export
  of the training run `docquad_v6_rot40_from_v2_receipts`, epoch 35, with
  NCNN's converter pnnx 20261020 (`scripts/models_to_ncnn.sh docquad`).
  SHA-256 `121915af06b3f5856cd15c42c0e5ed8d28b7de93d2ea9f0adf62b84637cece9f`
  (`docquadnet256.ncnn.param`),
  `e48a54ab5d9ca1a2b4f23aa1f2b56b2480c3bb19caa036e02e1d7d0413b41c0a`
  (`docquadnet256.ncnn.bin`)
- Training code: the `training/` directory of
  [MakeACopy](https://github.com/egdels/makeacopy) (Apache-2.0).

## Training data

None of these datasets, nor any image, label or derivative of them, is
included in Sheet.

| Dataset | Licence | Use |
|---|---|---|
| [UVDoc](https://github.com/tanguymagne/UVDoc-Dataset), Floor Verhoeven et al., "Neural Grid-based Document Unwarping" (SIGGRAPH Asia 2023) | MIT | pretraining: document geometry and perspective |
| SmartDoc 2015, Jean-Christophe Burie et al., "ICDAR 2015 Competition on Smartphone Document Capture and OCR (SmartDoc)" | CC BY 4.0 | fine-tuning on smartphone photos of documents |
| [CORD](https://github.com/clovaai/cord), Jaewook Kim et al., "CORD: A Consolidated Receipt Dataset for Post-OCR Parsing" (ICDAR 2019), NAVER Clova AI | CC BY 4.0 | fine-tuning on receipts |
| [Describable Textures Dataset (DTD)](https://www.robots.ox.ac.uk/~vgg/data/dtd/), M. Cimpoi et al., "Describing Textures in the Wild" (CVPR 2014) | no explicit licence in the distribution | textures pasted in as photo backgrounds during training |

The original images remain the property of their authors; attribution for
the CC BY 4.0 datasets is given here as that licence requires.
