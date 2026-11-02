# Accuracy Strategy: Every Finding Turned Into a Decision

**Scope:** Android, TD3 passports, MRZ only, offline, SDK-owned UI, airline use.

**Date:** 2026-11-02

This document consolidates `docs/research/01-mrz-scanning-techniques.md` (techniques and
vendor approaches) and `docs/architecture/01-sdk-architecture.md` (modules) into a single
set of accuracy decisions. Each decision names the finding behind it, the module that
owns it, and the parameter or gate that makes it enforceable.

The target is not "high accuracy". It is two numbers:

| Metric | Target | Why |
|---|---|---|
| False accept rate (all checks pass, a field is wrong) | below 1 in 10,000 verified reads | A wrong APIS record is worse than a failed scan |
| Verified-read rate within 10 s on the lowest device tier, in realistic conditions | above 97% | Gate-agent throughput |

Everything below serves those two numbers, in that order.

---

## 1. Where accuracy comes from, ranked by impact

Ordered by expected contribution, based on the figures in the research. Build in this
order when under time pressure.

| Rank | Lever | Evidence | Owner module |
|---|---|---|---|
| 1 | Multi-frame weighted integration with a real stopping rule | 56% to 88% detection with fusion (Hartl 2015); 66.7% to 81.8% exact match with temporal voting (plate study); best-half-of-frames with per-character weights was best (Smart Engines) | `mrz-core/integrate` |
| 2 | MRZ-specific 37-class recognizer emitting per-position probabilities | Generic engines fail on `<` runs and confusables; dedicated nets reach ~99.25% char F1 | `mrz-engine-tflite` |
| 3 | Check digits used as a gate, not a hint | Check-verified rate is the metric vendors move (58.6% to 95.5% in one benchmark) | `mrz-core/parse` |
| 4 | Visual-zone cross-check of document number, DOB, expiry | Regula and Microblink both cross-check zones; two independent reads agreeing is what drives residual error to zero | `mrz-engine-tflite/VizReader`, `mrz-core/result` |
| 5 | Capture quality gate with user guidance | Glare produces confident wrong digits; blur defeats resolution; both are preventable at capture | `mrz-engine-api/QualityGate`, `mrz-ui` |
| 6 | Dedicated detector plus rectification to a canonical strip | Learned detectors at ~100% band detection, under 10 ms; rectification needed before any recognizer | `mrz-engine-tflite`, `mrz-engine-api/Rectifier` |
| 7 | Field-aware confusable correction with probability margins | 0/O, 1/I, 5/S, 8/B, 2/Z are all valid MRZ chars; only per-field alphabets plus check digits resolve them | `mrz-core/recognize` |
| 8 | Camera configuration | CameraX drops analysis resolution when ImageCapture is bound; autofocus hunting is the top failure on budget phones | `mrz-camera` |
| 9 | Real-document regression corpus and per-issuer parser fixes | Regula's release notes are a decade of country-parser and font fixes | `tools/eval`, `mrz-core/parse` |
| 10 | Training data: synthetic plus real fine-tune | Synthetic-only detector dropped to 88% on real docs; mixed data cut error further | `tools/synth`, `tools/training` |

---

## 2. Decisions per pipeline stage

### 2.1 Camera (`mrz-camera`)

| Decision | Value | Finding |
|---|---|---|
| Use cases bound | `Preview` + `ImageAnalysis` only. Never bind `ImageCapture` | Binding all three cut analysis from 1440x1080 to 1024x768 |
| Analysis resolution | `ResolutionSelector` target 1920x1080, fallback closest-higher then closest-lower; reject sizes below 1280x720 | Budget phones default to 640x480, too low for MRZ |
| Backpressure | `STRATEGY_KEEP_ONLY_LATEST`, single-thread executor | Never queue frames; stale frames are worthless for voting |
| Pixel data | Luma plane only, stride-aware | MRZ is black on white; chroma is noise and copy cost |
| Focus | Continuous AF with metering point on the detected band; lock focus once band height is stable for 5 frames; unlock if band lost | AF hunting is the top reported failure on budget devices |
| Exposure | AE metering on the band; torch `AUTO` turns on when band contrast is below threshold | Low contrast is a listed failure hint in production SDKs |
| Working distance | UI guide sized so the MRZ band spans 70 to 90% of frame width at the guide | Character height drives recognizer accuracy more than megapixels |
| Device quirks | Per-model table: min resolution override, delegate denial, known noisy 1080p devices | Some devices advertise 1080p and deliver compressed noise |

### 2.2 Detection (`mrz-engine-tflite/TfliteMrzDetector`)

| Decision | Value | Finding |
|---|---|---|
| Architecture | BlazeFace-style anchor detector, 0.4 to 1 MB, input 320x320 luma | MobileMRZNet under 10 ms at ~400 KB |
| Output | Four corners of the MRZ band plus confidence, not an axis-aligned box | Rectification needs the quadrilateral |
| Two-pass refinement | If band height is under 12% of input height or aspect is outside 7:1 to 12:1, crop ROI with 25% margin and run again | YOLO-MRZ two-pass trick |
| Acceptance | Confidence above 0.6 and geometric sanity (aspect, two-line height ratio) | Structural rejects are cheaper than recognizer time |
| Fallback | If the detector fails for 30 consecutive frames with a document plausibly present, run a cheap text-band heuristic (horizontal projection profile) to produce a hint, never a read | Heuristics are too weak to read from but fine for guidance |

### 2.3 Quality gate (`mrz-engine-api/QualityGate`)

Measured on the rectified band, not the whole frame. Thresholds are starting points to be
tuned on the corpus; the gate must be data-driven, not hand-tuned forever.

| Check | Measure | Reject when | Hint |
|---|---|---|---|
| Blur | Variance of Laplacian, normalised by band height | below corpus 20th percentile of verified reads | Hold still |
| Glare | Fraction of band pixels above 250 in connected blobs wider than 2 characters | above 2% | Tilt to avoid reflection |
| Size | Band height in source pixels | below 44 px (about 20 px per line) | Move closer |
| Cut off | Any corner within 8 px of frame edge | always | Fit the whole passport |
| Tilt | Angle of the band's long axis | above 12 degrees | Hold flat |
| Perspective | Ratio of left and right band edge lengths | outside 0.85 to 1.15 | Hold parallel |
| Curl | Residual of each line's baseline against a straight fit | above 0.8 px per 100 px | Flatten the page |
| Contrast | Difference between 5th and 95th luma percentiles in band | below 60 | Find better light (torch auto) |

Rejected frames never reach the recognizer and never vote. The hint shown is the first
failing check in the table order, debounced for 400 ms so the banner does not flicker.

### 2.4 Rectification (`mrz-engine-api/Rectifier`)

| Decision | Value | Finding |
|---|---|---|
| Transform | Homography from the four corners, applied to the full-resolution luma plane, bilinear sampling | Hartl pipeline rectifies and upsamples before OCR |
| Canonical output | Two strips, each 44 cells x 32 px tall, cell width 20 px (880x32 per line) | Monospaced OCR-B makes fixed cells reliable once rectified |
| Line split | From the detector's two-line geometry, refined by horizontal projection valley | Heuristic line splitting alone fails on curved pages |
| Curl handling | Gate rejects strong curl; mild curl absorbed by a per-line vertical re-centering using the projection profile | Thesis poster: homography-only fails on strongly curved documents |

### 2.5 Recognition (`mrz-engine-tflite/TfliteMrzRecognizer`)

| Decision | Value | Finding |
|---|---|---|
| Alphabet | Exactly 37 classes: A-Z, 0-9, `<` | Whole problem is simpler than open OCR |
| v1 architecture | Per-cell CNN classifier over a 32x20 cell with 4 px context on each side, shared weights, batched 44 cells per line | Simplest way to get a clean per-position distribution; fast on CPU |
| v2 architecture | CRNN + CTC over the whole 880x32 strip, MobileNet-style backbone, greedy CTC decode in Kotlin, collapsed to 44 positions | More tolerant to residual segmentation error; CTC decoder is not exportable to TFLite |
| Output contract | 44 x 37 softmax per line, temperature-calibrated on held-out real data so margins mean something | Correction and voting both depend on calibrated probabilities |
| Quantisation | int8 weights and activations, calibrated on real crops | Smart Engines runs 8-bit and 4-bit nets for speed with negligible loss |
| Delegate | XNNPACK CPU as floor; GPU delegate opt-in; NNAPI only where `DeviceQuirks` allows | CPU-only must meet budget on the lowest tier |

### 2.6 Parsing and correction (`mrz-core`)

| Decision | Value | Finding |
|---|---|---|
| Check digits | 7-3-1 mod 10, `<` = 0, on document number, DOB, expiry, optional data, composite (positions 1-10, 14-20, 22-43) | ICAO 9303 Part 3 |
| Fixed positions | Line 1 position 1 must be `P`; sex in {M, F, `<`}; issuing state and nationality must be on the ICAO list including D, GBD, GBN, GBO, GBP, GBS, UNO, UNA, UNK, XXA, XXB, XXC, XXX, RKS, EUE | Fixed-alphabet positions allow near-certain correction |
| Field alphabets | Numeric: dates, check digits. Alpha: document code, states, names, sex. Alphanumeric: document number, optional data | Confusable pairs are only resolvable per field |
| Correction policy | For each position, restrict the distribution to the field alphabet and re-normalise. Pick the argmax. Then, per field with a failing check digit: if exactly one position has top-2 margin under 0.15 and swapping to its runner-up makes the check pass, record it as a candidate correction. Never apply more than one correction per field per frame | Over-correction toward a passing checksum manufactures plausible wrong data |
| Correction acceptance | A candidate correction is accepted only if the integrated (multi-frame) distribution also prefers it or a later frame confirms it | Single-frame "fixes" are how false accepts happen |
| Document number | No statistical correction beyond alphabet restriction; relies on check digit, voting and VIZ agreement | Loose structure makes correction unreliable here |
| Names | No correction, no check digit; confidence reported from voting margin only; truncation flag set when last name position is a letter | Composite does not cover line 1 |
| Dates | `<` digits allowed in DOB, parsed as unknown; century pivot: DOB in the past, expiry within the next 20 years | ICAO permits unknown date digits |
| Optional data check | Accept `<` or `0` when the field is all fillers | Issuer practice varies |
| Output | Raw lines, corrected lines, every correction with position and probability, per-check status | Auditability for APIS |

### 2.7 Multi-frame integration (`mrz-core/integrate`)

| Decision | Value | Finding |
|---|---|---|
| Alignment | None needed; positions are fixed at 44 per line | MRZ is fixed-length |
| Accumulator | Per position, weighted sum of per-frame distributions; argmax at read time | Per-character weighting outperformed frame-only weighting |
| Frame weight | Normalised blur score x (1 - glare fraction) x detector confidence | Focus-weighted combination was best in the literature |
| Per-character weight | Local blur score over the cell x the cell's own max probability | A sharp frame can still have a few smeared characters |
| Frame selection | Keep a sliding window of the best 50% of the last 12 voted frames by frame weight; drop the rest from the accumulator | Best-half combination performed best |
| Structural filter | Frames whose argmax string fails length, `P` position, or has a non-alphabet char in a fixed-alphabet field are discarded before voting | Garbage frames poison the vote |
| Reset | If the band geometry jumps by more than 30% or a different document number with margin above 0.5 appears for 3 frames, clear the accumulator | Agent scans the next passenger without closing the screen |
| Processing strategy | If recognizer latency is over 1.5x frame interval on this device, recognise only frames passing a stricter quality bar; otherwise recognise every gated frame | Smart Engines 2021: strategy depends on recognise-vs-acquire cost |

### 2.8 Stopping rule (`mrz-core/integrate/StoppingRule`)

Return `Success` only when all hold:

1. Integrated string parses and every present check digit passes.
2. Integrated string unchanged for `stableFrames` = 3 consecutive voted frames.
3. Minimum top-1 minus top-2 margin over all 88 positions is above `minMargin` = 0.25.
4. At least 5 frames have voted.
5. If VIZ cross-check is on and a VIZ read exists: document number, DOB and expiry agree
   exactly. On disagreement, continue; after 3 further agreeing MRZ frames with a second
   VIZ read still disagreeing, return `Unverified(vizMismatch)` regardless of config.
6. No accepted correction is older than 2 voted frames without re-confirmation.

Expected-change approximation (Bulatov): also stop early if the accumulator's predicted
change from one more frame is under 0.5% at every position **and** conditions 1, 3 and 5
hold. This trims a frame or two on good devices without weakening the gates.

Timeout at 15 s. `Unverified` returned with best state only if `allowUnverifiedResult`;
otherwise `Failed(Timeout, lastHint)`.

### 2.9 Visual-zone cross-check (`mrz-engine-tflite/TfliteVizReader`)

| Decision | Value | Finding |
|---|---|---|
| Fields | Document number, date of birth, date of expiry | The three fields with APIS consequences and check digits; also the ones a glare streak can flip cleanly |
| Localisation | TD3 data-page geometry is standardised relative to the MRZ band: locate the band, then search fixed relative regions for the three fields; a small line detector confirms | Regula's template database turns detection into verification; for TD3 the MRZ itself is the template |
| Recognition | Same 37-class recognizer on the located lines, plus a 10-class digit head for date formats that use spaces or separators; date formats normalised per issuing state | Reuses the calibrated model |
| Schedule | First attempt once MRZ checks pass; retry every 4 voted frames until agreement or timeout | Keeps VIZ cost off the critical path |
| Trust | VIZ never overrides MRZ. Disagreement blocks `Success` | MRZ has check digits; VIZ does not |

---

## 3. Defences against false accepts (the metric that matters most)

A false accept needs: a wrong character, check digits that still pass, voting that
agrees, and no cross-check. Each layer below removes one of those conditions.

1. **Two compensating errors in one field** pass a mod-10 check. Defence: margin gate
   (condition 3) plus stability (condition 2). Two independent errors rarely survive
   three frames of weighted voting with a 0.25 margin.
2. **Confident glare flip** (a streak turns 3 into 8 sharply). Defence: glare gate
   rejects the frame; per-character weight downgrades the cell; VIZ cross-check catches
   the survivor.
3. **Over-eager correction** to satisfy a checksum. Defence: one correction per field
   per frame, margin under 0.15 only, must be confirmed by the integrated distribution.
4. **Document swap mid-session** mixing two passports in one accumulator. Defence: reset
   on geometry jump or competing document number.
5. **Names** have no check digit. Defence: reported with their own confidence and the
   truncation flag; the airline UI shows them for agent confirmation, which the
   diagnostics record as a hint count, not as text.
6. **Model drift on a new issuer's print run.** Defence: nightly eval on the private
   corpus with a per-issuer breakdown; a drop of more than 0.5 points on any issuer
   blocks the model release.

---

## 4. Training and data recipe

| Step | Decision |
|---|---|
| Synthetic generator (`tools/synth`) | TD3 strings with valid check digits, drawn from real distributions of issuing states, name lengths, filler patterns and unknown-date cases; rendered in at least four OCR-B variants onto inpainted real data-page templates from 30+ issuers |
| Augmentation | Motion blur, defocus, specular blobs, perspective within gate limits and beyond (so the gate's margins are learned), page curl, shadows, uneven illumination, sensor noise, JPEG and YUV artefacts, laminate holograms, finger occlusion of up to 3 characters, low contrast |
| Real data | A private capture corpus: every issuing state the airline serves, worn and new documents, every device tier, lighting from gate to apron. Labelled by MRZ string (which is self-checking) and by band corners. Kept outside git; manifests only |
| Split | Detector: 80/20 synthetic/real in training. Recognizer: pretrain synthetic, fine-tune real with low learning rate, early-stop on real validation |
| Calibration | Temperature scaling on real validation so a 0.25 margin is meaningful |
| Public sets | MIDV-500 and MIDV-2020 passports are **test only**, reported separately, never trained on |
| Hard-negative mining | Every false accept and every `Unverified` from the device lab is added to validation with its frames; a sample goes to training only after the eval set has been frozen for the release |

---

## 5. Measurement and release gates

Run by `tools/eval` nightly and on every model or core change.

| Metric | Definition | Gate |
|---|---|---|
| Exact-MRZ accuracy | All 88 characters correct on `Success` | no regression beyond 0.2 points |
| False accept rate | `Success` with any field wrong | zero on the eval set; any occurrence blocks release and becomes a test case |
| Check-verified rate | Sessions ending in `Success` | no regression beyond 0.5 points on any device tier |
| Per-issuer accuracy | Exact-MRZ grouped by issuing state | no issuer drops more than 0.5 points |
| Time to result | Median and p95 from first detection to `Success` | p95 under 6 s on tier 1, under 10 s on tier 3 |
| Hint distribution | Share of frames rejected per hint | reviewed, not gated; tells the UX team where capture fails |
| Correction rate | Share of `Success` results that include an accepted correction | reviewed; a rise means the recognizer got worse, not that correction got better |
| VIZ disagreement rate | Share of sessions blocked by cross-check | reviewed; a rise on one issuer means a VIZ layout change |

Device tiers for the lab: tier 1 (recent flagship), tier 2 (mid-range, 3 years old),
tier 3 (entry level, 2 GB RAM, armeabi-v7a still present in fleet). All gates are
measured on tier 3 unless stated.

---

## 6. What this changes in the architecture document

- `mrz-core/recognize/FieldAwareCorrector` gains the one-correction-per-field,
  margin-under-0.15, confirmed-by-integration rules from section 2.6.
- `mrz-core/integrate/FrameIntegrator` gains the best-50%-of-last-12 window and reset
  conditions from section 2.7.
- `StoppingRule` gains conditions 4 to 6 and the expected-change early stop.
- `mrz-engine-tflite/TfliteVizReader` is specified (fields, localisation, schedule).
- `mrz-camera/FocusController` gains the lock-after-stable and unlock-on-loss behaviour.
- `tools/eval` gains the per-issuer breakdown and the false-accept zero gate.

These are refinements of the existing module boundaries, not new modules.

---

## 7. Why this reaches vendor-grade without a template database

Regula's template database exists because it reads 17,000 document layouts. This SDK
reads one: the TD3 data page, whose MRZ geometry, font, alphabet, field positions and
check digits are fixed by ICAO. The MRZ band is the template. Everything Regula gains
from identification (known layout, known font, cross-zone fields at known offsets) is
available here by construction. What remains is execution: a calibrated recognizer,
disciplined voting, a conservative stopping rule, a cross-check, and a corpus that keeps
it honest release after release.
