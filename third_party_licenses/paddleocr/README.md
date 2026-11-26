# PaddleOCR PP-OCRv5 mobile and PP-OCRv6 small

The text detector and recognisers in `app/src/main/assets/paddleocr`,
converted to NCNN's format. Copyright (c) PaddlePaddle Authors.
Licensed under the Apache License, Version 2.0 (`LICENSE-Apache-2.0.txt`
in this directory), separately from the GPL-3.0-or-later that covers
Sheet's code. The conversion does not change the licence.

- Source of weights and dictionaries:
  [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR), downloaded with
  [PaddleX](https://github.com/PaddlePaddle/PaddleX) (both Apache-2.0).

## Files

| File | Model | Reads |
|---|---|---|
| `det.ncnn.*` | `PP-OCRv5_mobile_det` | text boxes (detector) |
| `latin_PP-OCRv5_mobile_rec.ncnn.*` | `latin_PP-OCRv5_mobile_rec` | Latin script: English, German, French, Polish, Turkish, … |
| `eslav_PP-OCRv5_mobile_rec.ncnn.*` | `eslav_PP-OCRv5_mobile_rec` | Russian, Ukrainian, Belarusian |
| `cyrillic_PP-OCRv5_mobile_rec.ncnn.*` | `cyrillic_PP-OCRv5_mobile_rec` | other Cyrillic languages |
| `arabic_PP-OCRv5_mobile_rec.ncnn.*` | `arabic_PP-OCRv5_mobile_rec` | Arabic, Persian, Urdu, Pashto |
| `devanagari_PP-OCRv5_mobile_rec.ncnn.*` | `devanagari_PP-OCRv5_mobile_rec` | Hindi, Marathi, Nepali, Sanskrit |
| `th_PP-OCRv5_mobile_rec.ncnn.*` | `th_PP-OCRv5_mobile_rec` | Thai |
| `el_PP-OCRv5_mobile_rec.ncnn.*` | `el_PP-OCRv5_mobile_rec` | Greek |
| `korean_PP-OCRv5_mobile_rec.ncnn.*` | `korean_PP-OCRv5_mobile_rec` | Korean |
| `PP-OCRv6_small_rec.ncnn.*` | `PP-OCRv6_small_rec` | Latin script, Chinese, Japanese (with the v5 models as a check) |
| `PP-OCRv5_mobile_rec.ncnn.*` | `PP-OCRv5_mobile_rec` | Chinese, Japanese, and the fallback |

Every model is two files: `<name>.ncnn.param` (the layers, text) and
`<name>.ncnn.bin` (the weights, single precision).
Each recogniser comes with its character dictionary,
`<model>_dict.txt`, taken from the model's upstream `inference.yml`.
SHA-256 of every file: `SHA256SUMS` in this directory
(`shasum -a 256 -c` in the assets directory checks them).

## Conversion

The ONNX files and the dictionaries of the v5 models are MakeACopy's
(Apache-2.0,
[github.com/egdels/makeacopy](https://github.com/egdels/makeacopy)); its
`scripts/` made them (steps 1 and 2). The Korean one was converted there
with the others but not shipped by MakeACopy. Step 3 is Sheet's.

1. Paddle inference weights downloaded with PaddleX
   (`download_paddleocr_v5_makeacopy_models.sh`).
2. Paddle to ONNX with [Paddle2ONNX](https://github.com/PaddlePaddle/Paddle2ONNX)
   (Apache-2.0), opset 11 (`convert_paddleocr_v5_all.sh`).
3. ONNX to NCNN with NCNN's converter
   [pnnx](https://github.com/Tencent/ncnn/tree/master/tools/pnnx)
   20261020, weights kept in single precision (`fp16=0`):
   `scripts/models_to_ncnn.sh <det|rec> <model.onnx> app/src/main/assets/paddleocr <name>`
   in Sheet.

Neither the Paddle weights nor the ONNX files are in Sheet. The NCNN
layers the models use are listed in `native/layers.config`, from which
Sheet's reduced NCNN is built.

## PP-OCRv6 small

`PP-OCRv6_small_rec.ncnn.*` comes from the Hugging Face release
[PaddlePaddle/PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
(Apache-2.0), revision `2f0724790c8b57946c89cc45d2fa79e405781f51`,
`inference.onnx` SHA-256
`5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`.
`scripts/paddle_v6_rec.py` checks that hash and converts the file as in
step 3 above (`scripts/models_to_ncnn.sh rec`); the dictionary is
`inference.yml`'s with the space PaddleOCR appends. `SHA256SUMS` pins the
shipped files.

## Trademarks

The Apache License 2.0 grants no trademark rights (section 6).
"PaddleOCR", "PaddlePaddle" and "PaddleX" are trademarks of their owners
(Baidu, Inc. and the PaddlePaddle authors). Sheet is not affiliated with,
endorsed by or sponsored by them.
