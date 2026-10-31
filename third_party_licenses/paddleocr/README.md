# PaddleOCR PP-OCRv5 mobile and PP-OCRv6 small

The text detector and recognisers in `app/src/main/assets/paddleocr`,
converted to ONNX Runtime's format. Copyright (c) PaddlePaddle Authors.
Licensed under the Apache License, Version 2.0 (`LICENSE-Apache-2.0.txt`
in this directory), separately from the GPL-3.0-or-later that covers
Sheet's code. The conversion does not change the licence.

- Source of weights and dictionaries:
  [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR), downloaded with
  [PaddleX](https://github.com/PaddlePaddle/PaddleX) (both Apache-2.0).

## Files

| File | Model | Reads |
|---|---|---|
| `det.ort` | `PP-OCRv5_mobile_det` | text boxes (detector) |
| `latin_PP-OCRv5_mobile_rec.ort` | `latin_PP-OCRv5_mobile_rec` | Latin script: English, German, French, Polish, Turkish, … |
| `eslav_PP-OCRv5_mobile_rec.ort` | `eslav_PP-OCRv5_mobile_rec` | Russian, Ukrainian, Belarusian |
| `cyrillic_PP-OCRv5_mobile_rec.ort` | `cyrillic_PP-OCRv5_mobile_rec` | other Cyrillic languages |
| `arabic_PP-OCRv5_mobile_rec.ort` | `arabic_PP-OCRv5_mobile_rec` | Arabic, Persian, Urdu, Pashto |
| `devanagari_PP-OCRv5_mobile_rec.ort` | `devanagari_PP-OCRv5_mobile_rec` | Hindi, Marathi, Nepali, Sanskrit |
| `th_PP-OCRv5_mobile_rec.ort` | `th_PP-OCRv5_mobile_rec` | Thai |
| `el_PP-OCRv5_mobile_rec.ort` | `el_PP-OCRv5_mobile_rec` | Greek |
| `korean_PP-OCRv5_mobile_rec.ort` | `korean_PP-OCRv5_mobile_rec` | Korean |
| `PP-OCRv6_small_rec.ort` | `PP-OCRv6_small_rec` | Latin script, Chinese, Japanese (with the v5 models as a check) |
| `PP-OCRv5_mobile_rec.ort` | `PP-OCRv5_mobile_rec` | Chinese, Japanese, and the fallback |

Each recogniser comes with its character dictionary,
`<model>_dict.txt`, taken from the model's upstream `inference.yml`.
SHA-256 of every file: `SHA256SUMS` in this directory
(`shasum -a 256 -c` in the assets directory checks them).

## Conversion

The files are MakeACopy's (Apache-2.0,
[github.com/egdels/makeacopy](https://github.com/egdels/makeacopy)),
byte for byte; its `scripts/` made them. The Korean one was converted
there with the others but not shipped by MakeACopy.

1. Paddle inference weights downloaded with PaddleX
   (`download_paddleocr_v5_makeacopy_models.sh`).
2. Paddle to ONNX with [Paddle2ONNX](https://github.com/PaddlePaddle/Paddle2ONNX)
   (Apache-2.0), opset 11 (`convert_paddleocr_v5_all.sh`).
3. ONNX to ORT with ONNX Runtime's basic graph optimisation
   (`convert_paddleocr_v5_to_ort_basic_all.py`).

Neither the Paddle weights nor the ONNX files are in Sheet. The operators
the models use are listed in `ort/paddleocr_v5.config`, from which Sheet's
reduced ONNX Runtime is built.

## PP-OCRv6 small

`PP-OCRv6_small_rec.ort` comes from the Hugging Face release
[PaddlePaddle/PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
(Apache-2.0), revision `2f0724790c8b57946c89cc45d2fa79e405781f51`,
`inference.onnx` SHA-256
`5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`.
`scripts/paddle_v6_rec.py` made it the way MakeACopy made the v5 files:
ONNX Runtime's basic optimisation, then the ORT format for ARM; the
dictionary is `inference.yml`'s with the space PaddleOCR appends. The
conversion is not byte for byte reproducible (two runs differ in their
bytes, not in their outputs); `SHA256SUMS` pins the shipped file.

## Trademarks

The Apache License 2.0 grants no trademark rights (section 6).
"PaddleOCR", "PaddlePaddle" and "PaddleX" are trademarks of their owners
(Baidu, Inc. and the PaddlePaddle authors). Sheet is not affiliated with,
endorsed by or sponsored by them.
