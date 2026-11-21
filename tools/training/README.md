# Model training

The Android engine (`mrz-engine-tflite`) expects three models listed in
`mrz-engine-tflite/src/main/assets/models.json`. None are committed to git. This
directory holds the recipe; the training code lands here as it is written.

## Contracts (must match `models.json`)

| Model | Input | Output | Notes |
|---|---|---|---|
| `mrz_detector` | `[1,320,320,1]` float 0..1, letterboxed luma | `[1,9]`: confidence, then 4 corners `(x,y)` normalised, clockwise from top-left | BlazeFace or YOLO-nano class, int8, target under 1 MB and under 15 ms on tier 3 |
| `mrz_recognizer` | `[1,32,880,1]` float 0..1, one rectified line, 44 cells of 20 px | `[1,44,37]` logits in `MrzAlphabet` order | v1: per-cell CNN with shared weights. v2: CRNN+CTC collapsed to 44 positions. int8. Temperature-calibrated; put the temperature in the manifest |
| `viz_line_detector` | `[1,256,512,1]` float 0..1, region above the band | `[1,3,5]`: per field (document number, DOB, expiry) confidence, `cx, cy, w, h` normalised | Only the three cross-check fields |

## Data

1. `tools/synth/generate_td3.py render` with several OCR-B variants and inpainted real
   data-page templates from 30+ issuers.
2. The private real capture corpus (manifests in `tools/corpus/`, images outside git):
   every issuing state served, worn and new documents, every device tier, lighting from
   gate to apron. Labelled by MRZ string (self-checking through the check digits) and
   band corners.
3. MIDV-500 and MIDV-2020 passport subsets are **test only**.

Splits: detector 80/20 synthetic/real in training. Recognizer: pretrain synthetic,
fine-tune real with a low learning rate, early-stop on real validation. Calibrate with
temperature scaling on real validation. Export with int8 post-training quantisation
calibrated on real crops.

## Release gate

A model ships only when `tools/eval` on the frozen eval set reports zero false accepts,
no issuer dropping more than 0.5 points of exact-MRZ accuracy, and p95 time-to-result
under 10 s on tier 3. Update the SHA-256 in `models.json` on every export.
