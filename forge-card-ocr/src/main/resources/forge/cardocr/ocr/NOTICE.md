# OCR models

These files are PaddleOCR (PP-OCR) models converted to ONNX, and they're read by `forge.cardocr.OcrCardNameReader`.

| File | What | SHA-256 |
|---|---|---|
| `detection_v3_det.onnx` | PP-OCRv3 text detection | `ee40e80071ba3a320d4efda75f3e22047a7d049e9bf7bcaaf9daea23fc21b935` |
| `languages_english_rec.onnx` | English text recognition | `4e16deb22c4da6468bdca539b2cd3c8687825538b67109177c47d359ab994cd7` |
| `languages_english_dict.txt` | The recogniser's characters | `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6` |

- **Source:** the ONNX conversions published at https://huggingface.co/monkt/paddleocr-onnx, of the models from https://github.com/PaddlePaddle/PaddleOCR. The OCR spike downloaded its other detector from that repository. That these three files came from its `detection/v3` and `languages/english` folders is inferred from their names, not yet checked against the repository.
- **Licence:** Apache License 2.0, as declared by PaddleOCR and the conversion repository. A copy is at https://www.apache.org/licenses/LICENSE-2.0.
- **Runtime:** they run on ONNX Runtime (https://github.com/microsoft/onnxruntime), MIT licence, which the dev build and Android bring in as a dependency.
