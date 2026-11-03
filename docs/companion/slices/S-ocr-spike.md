# Spike — Choose the OCR engine

**Throwaway.** Not merged. The only output is a results section added to `plan.md`.

## Why
Recognition reads a card's printed name, on the device, offline. Everything depends on OCR being accurate and fast enough on real phone photos. Find out before building.

## Input needed
About 30 phone photos of real English cards, mixed sleeved and unsleeved, in normal indoor light, each named with the correct card name. **Provided by you.**

## Do
Write a small standalone Java harness outside the Maven modules (in the scratchpad). For each candidate, run: crop to the name band → OCR → fuzzy-match against all card names, using Levenshtein similarity and the names under `forge-gui/res/cardsfolder`.

Candidates:
1. ONNX Runtime with a PP-OCR recognition model (`com.microsoft.onnxruntime`).
2. Tesseract.

Try the crop with and without simple preprocessing (grayscale, contrast). Photos won't have the guide frame, so hand-crop or use a fixed band if needed, and say so in the results.

## Confirm and record
- Top-1 and top-3 accuracy per engine, listing failures and why.
- Time per photo on this machine, and a phone estimate from published benchmarks or a real run.
- APK size increase per ABI (native library + model).
- Licence of the engine and the model file. Must be GPLv3-compatible.
- Latest version of each, and how each is distributed.
- Whether the desktop and Android Java APIs match.

## Oracle
- WHEN the harness runs on the photo batch THE RESULT SHALL be a table of the numbers above, reproducible from a stated command.
- **Pass:** one engine has at least 90 % top-3, a credible path to 2 s on a phone, and an acceptable licence.
- **Fail:** stop and revisit the spec with you. Do not weaken the target.

## After
- Record the choice in `plan.md` section 4.
- You contact the Forge maintainers with the numbers and the APK size before slice 0 starts.

## Out of scope
Any change to Forge modules. Any UI.
