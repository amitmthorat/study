# High-Accuracy Passport MRZ Scanning: Techniques Research

**Scope of the SDK this informs:** Android, MRZ read only (no NFC), TD3 passports only,
SDK-owned camera UI, fully offline, airline check-in and boarding use.

**Date:** 2026-11-02

This document collects what published research, vendor evaluations, and open-source
projects say about reading the Machine Readable Zone (MRZ) accurately from a phone camera.
It ends with a recommended pipeline for our SDK. Accuracy figures are quoted as reported
and are not directly comparable across sources (different datasets, different metrics).

---

## 1. The pipeline that accurate systems converge on

Every serious system, academic or commercial, uses the same six-stage pipeline. Generic
"OCR the whole frame" approaches are consistently reported to fail on MRZ.

```
camera frame
  -> 1. MRZ detection (find the two-line band, as a quadrilateral)
  -> 2. rectification (homography warp to a canonical, fronto-parallel crop)
  -> 3. recognition (MRZ-specific recognizer emitting per-character probabilities)
  -> 4. parsing + validation (ICAO 9303 structure, field alphabets, check digits)
  -> 5. multi-frame integration (weighted per-character voting across frames)
  -> 6. stopping rule (decide when the result is trustworthy, then stop the camera)
```

Stages 5 and 6 are what separate a demo from a production reader. Single-frame results
are unreliable on phones; the accuracy comes from combining frames and from knowing when
to stop.

---

## 2. Stage 1: MRZ detection

### What the evidence says

- **Heuristic text-group detection (Hartl, Arth, Schmalstieg, VISAPP 2015).** Finds text
  groups, checks their spatial layout for MRZ structure, estimates a transform, rectifies
  characters, runs a custom OCR, and fuses across frames. Single-shot detection rate on
  low-resolution phone frames was only **56.12%**; a multi-frame figure of **88.18%** is
  reported. Character read rate was 98.58%. This shows heuristics alone are fragile and
  frame fusion is essential.
- **Learned detectors are now standard.**
  - **YOLO-MRZ** (Gayer, Ershova, Arlazarov): ~1 MB, 62 FPS on iPhone SE 2020, reported
    as 83x faster than Tiny YOLO v3. Important trick: **run the detector twice.** If the
    first box is too small or fails geometric checks, crop a region of interest around it
    and run the same detector again on the crop.
  - **MobileMRZNet** (2024): BlazeFace-based, **~400 KB**, under **10 ms** per frame on
    mobile. Reported 88% MRZ extraction on real documents when trained on synthetic data
    only, which is the main caveat: real fine-tuning data matters.
  - **CNN MRZ extraction paper (arXiv 2009.05489, IJDAR 2021):** 100% single-frame
    detection on its dataset and 99.25% character macro-F1. The authors state plainly
    that standard OCR struggles with smartphone passport photos and a dedicated network
    is needed.
- **A fast "locate" step before a full read is useful for UI.** One SDK exposes a
  millisecond-scale band locator with no character recognition, used only to drive the
  viewfinder overlay and give guidance.

### Design implications

- Ship a tiny dedicated detector (BlazeFace or YOLO-nano class, 0.4 to 1 MB, TFLite).
  Output the MRZ band as a quadrilateral (four corners), not an axis-aligned box, so
  rectification can be done from it.
- Run it on a downscaled luma frame (for example 320 to 512 px wide). Run the two-pass
  ROI refinement when the band is small.
- Reject frames where the band touches the frame edge (cut off), is below a minimum
  height in pixels, or is tilted beyond a threshold. Report the reason to the UI.

---

## 3. Stage 2: Rectification

- Camera captures are never fronto-parallel. Papers on mobile document OCR rectify by
  computing a homography from the detected quadrilateral or from horizontal and vertical
  vanishing points of the text lines (Williem et al. propose a fast version with an
  adaptive skip when the vertical vanishing point is unreliable).
- The Hartl pipeline explicitly includes "rectification and upsampling" before OCR:
  warp each line to a fixed canonical height so the recognizer always sees characters
  at the same scale.
- A Technikum Wien thesis poster reports that heuristic rectification and line splitting
  fails on **strongly curved pages**. Passports held open by one hand curve. A
  quadrilateral homography handles tilt and perspective but not page curl; a learned
  detector that predicts per-line baselines, or a thin-plate warp, handles curl better.

### Design implications

- Warp to a canonical crop with a fixed line height (for example 2 lines x 44 characters,
  each line resampled to around 32 px tall). Do this on the full-resolution luma plane,
  not on the downscaled detection frame.
- Treat page curl as a known failure mode: detect it (baseline curvature) and guide the
  user to flatten the page rather than trying to read through it.

---

## 4. Stage 3: Recognition

### Generic OCR engines underperform on MRZ

- **ML Kit Text Recognition.** Developer reports consistently say it struggles with the
  `<` filler runs and with reliably capturing both lines. The common workaround is to
  grab the second line only and regex-validate it, which throws away most of the
  structure. ML Kit has no OCR-B tuning and no way to restrict the alphabet. The bundled
  model does work offline, so it is a viable prototype engine but not a production one.
- **Tesseract (including PassportEye and OCR-B fine-tunes).** Expects clean, deskewed,
  binarized input, which raw phone captures are not. Hobby projects report frequent
  misreads in the passport font. The MIDV-500 and MIDV-2020 baselines were produced with
  Tesseract and land in the 60 to 88% range for MRZ lines depending on conditions.
- One vendor's own benchmark on MIDV-500 passport images (3,600 frames) reported only
  **44.5% exact-MRZ accuracy** and **59.4% success rate**. MIDV-500 is a hard, low-quality
  video dataset, but this shows how far "works on scans" is from "works on phones".

### What MRZ-specific recognizers do

- **Restricted alphabet.** MRZ uses exactly 37 symbols: `A-Z`, `0-9`, `<`. A dedicated
  classifier is a 37-class problem, which is much easier than open-vocabulary OCR.
- **Two architectures are used:**
  1. **Segment then classify.** Split the rectified line into 44 fixed-width cells
     (OCR-B is monospaced, which makes this reliable once rectified) and run a small CNN
     per cell. Simple, fast, trivially gives per-character probabilities.
  2. **Line-level CRNN + CTC.** One pass per line with a MobileNet-style backbone and a
     recurrent or convolutional sequence head. More tolerant of slight segmentation
     error. Note: TFLite does not export the CTC decoder, so greedy or beam CTC decoding
     must be implemented in app code.
- **Emit a probability distribution per character position, not just the argmax.**
  Every downstream technique that produces accuracy (field-aware correction, constrained
  decoding against check digits, weighted multi-frame voting) needs the alternatives and
  their confidences.
- Reported figures for dedicated models: 99.25% character macro-F1 (arXiv 2009.05489);
  one study reports 100% after three consecutive agreeing frames.

### Training data

- Real passport images cannot be collected at scale for privacy reasons. The accepted
  recipe is **synthetic generation plus a small real fine-tuning set**:
  - Take real passport template images from many countries (one tutorial used 25),
    inpaint out the original MRZ, generate random ICAO-compliant MRZ strings with valid
    check digits, render them in OCR-B, and composite back.
  - Augment aggressively: motion and defocus blur, specular glare blobs, perspective and
    rotation, page curl, uneven illumination and shadows, sensor noise, JPEG and YUV
    chroma artifacts, holographic laminate overlays, low contrast, partial occlusion by
    fingers.
  - A 2018 SPIE paper on synthetic data for mobile OCR found recognition rates on
    generated images were very similar to real-world images, which validates the
    approach for standardized fonts like OCR-B.
  - A licence-plate OCR paper (transferable lesson) found synthetic-only training works
    but mixing in partly-real data reduces error further. MobileMRZNet's drop to 88% on
    real documents when trained synthetic-only is the same lesson.
- OCR-B font files are freely available. Use several OCR-B variants, since issuing
  countries' printers differ.
- Use MIDV-500 and MIDV-2020 as **test sets only** (they are public, so models trained on
  them give inflated numbers).

---

## 5. Stage 4: Parsing and validation (ICAO 9303)

### Check digits

- Algorithm (Doc 9303 Part 3): weights `7, 3, 1` repeating, characters valued `0-9 = 0-9`,
  `A-Z = 10-35`, `< = 0`; sum modulo 10.
- TD3 line 2 check digits: document number (pos 10), date of birth (pos 20), expiry
  (pos 28), optional data / personal number (pos 43), and a **composite** check digit
  (pos 44) over positions 1-10, 14-20, and 22-43.
- Check digits are the single strongest accuracy lever available. One vendor reports
  fully check-digit-verified reads going from 58.6% to 95.5% on a 266-passport set after
  pipeline improvements; the metric itself is what you want to track.
- Limits: a check digit catches a single substitution almost always, but two errors can
  cancel. Check digits are public and unkeyed, so they prove read correctness, not
  document authenticity.

### Confusable characters and field-aware correction

- Documented OCR-B confusable pairs: `0/O`, `1/I`, `5/S`, `8/B`, `2/Z`, `6/G`, `0/D`,
  `<` vs `C` or `K` when degraded. Whitelisting the alphabet does **not** fix these, since
  both members of each pair are valid MRZ characters.
- The fix is **field-aware correction**: each field has a known alphabet.
  - Numeric only: both dates, all check digits.
  - Alphabetic only: document code position 1 (always `P` for passports), issuing state,
    nationality, names, sex (`M`, `F`, `<`).
  - Alphanumeric: document number, optional data. These are the weakest fields because
    their structure is loose; one study notes statistical correction is unreliable there.
- Correct by **re-ranking the recognizer's alternatives within the field's alphabet**,
  then confirm with the check digit. Do not blindly swap `O` for `0`; pick the
  highest-probability allowed alternative and require the check digit to pass.
- Constrained decoding: when exactly one field fails its check digit and a single
  substitution among the top-2 alternatives of one position fixes it, accept it only if
  the probability margin is small (the recognizer was genuinely unsure) and a later frame
  confirms. Otherwise you manufacture plausible-looking wrong data, which is worse than
  a failed read in an airline context.
- Keep the raw recognized string, the corrected string, and the per-field check status in
  the result. Auditability matters for APIS submissions.

### TD3 parsing edge cases to handle

- `<` to space in names; primary and secondary identifiers split on `<<`; trailing
  truncation when a name does not fit (an alphabetic character in the last name position
  signals truncation).
- Unknown date digits may be `<` (for example approximate birth year); the check digit is
  computed with `<` as 0.
- Optional data (personal number) may be all fillers; its check digit may then be `<` or
  `0` depending on issuer. Accept both.
- Two-digit years: pivot date of birth and expiry separately (expiry is always future
  relative to issue, birth is always past).
- Nationality and issuing state codes include non-ISO values: `D` (Germany), `GBD`, `GBN`,
  `GBO`, `GBP`, `GBS` (UK variants), `UNO`, `UNA`, `UNK`, `XXA`, `XXB`, `XXC`, `XXX`
  (stateless and refugee codes), `RKS` (Kosovo), `EUE`. Validate against the ICAO list,
  not plain ISO 3166-1 alpha-3.
- Second character of the document code distinguishes passport subtypes (diplomatic,
  official, emergency). Airlines care about this. Preserve it.
- Document-number overflow into optional data is a TD1 rule, not TD3. TD3 numbers are
  9 characters.

---

## 6. Stage 5: Multi-frame integration

This is where most of the accuracy comes from. The Smart Engines group (Bulatov,
Arlazarov, Petrova and others) has the most complete published treatment.

- **Why:** single frames are degraded by motion blur, glare streaks, and autofocus hunting
  differently each frame. Combining frames averages those out. Hartl 2015 saw detection
  go from 56% to 88% with fusion; a licence-plate study (not MRZ, but the same mechanism)
  saw exact-match go from 66.7% best-single-frame to 81.8% with temporal voting.
- **How:** MRZ lines are fixed length (44), so positions align trivially and no
  ROVER-style sequence alignment is needed. For each position, accumulate a weighted sum
  of the per-frame probability distributions, then take the argmax.
- **Weights:**
  - A frame-level weight from an image quality estimate (focus measure on the MRZ crop,
    glare coverage).
  - A **per-character weight**, because a frame can be sharp overall but locally blurred
    or glared on a few characters. A patent from this group (US 11,640,720) exists on
    per-character weighting; the published finding is that combining roughly the best
    half of frames with per-character and focus weighting performed best.
- Frames that fail structural parsing (wrong length, impossible characters in fixed
  positions) should be dropped before voting, not down-weighted.
- Reset the accumulator when the detected document clearly changes (large jump in band
  geometry or a different document number emerging with high confidence).

---

## 7. Stage 6: Stopping rule

- The naive rule "stop on first frame where all check digits pass" is wrong often enough
  to matter: two compensating errors or a lucky confusable swap can pass check digits.
- Bulatov et al. formalize stopping as an optimal stopping problem (monotone sequential
  decision model, IJDAR 2019) and give a practical approximation: estimate how much the
  **next** combined result would change the current one; stop when the expected change
  falls below a threshold. A patent (US 11,816,910) covers this approximation.
- Practical rule for our SDK, in order:
  1. The integrated result parses and **all** check digits pass.
  2. The integrated result has been **stable for N consecutive frames** (N = 3 is the
     figure reported to reach 100% in one study; make it configurable).
  3. The minimum per-position probability margin across all positions exceeds a
     threshold (no position is a coin flip between confusables).
  4. A hard timeout (for example 10 to 15 s) after which the SDK returns the best
     partial result flagged as unverified, or a failure with hints.
- Processing strategy: the same group's 2021 paper compares strategies. When per-frame
  recognition is about as fast as frame acquisition, process every frame. When
  recognition is much slower than acquisition, pick the best frames by a quality
  predictor and recognize only those. On low-end Android, the second regime applies, so
  a cheap quality gate in front of the recognizer is worth it.

---

## 8. Capture quality gating and user guidance

The UI is ours, so we can close the loop with the user. Sources agree on these checks,
and one SDK exposes them as per-frame failure hints, which is the right API shape.

| Check | Measure | Hint to user |
|---|---|---|
| Blur | Variance of Laplacian on the rectified MRZ crop, thresholded | "Hold still" |
| Glare | Area of saturated connected components over the band | "Tilt slightly to avoid reflection" |
| Too small | MRZ band height in pixels below minimum | "Move closer" |
| Cut off | Band touches frame edge | "Fit the whole passport" |
| Tilt / perspective | Quadrilateral aspect and angle | "Hold flat and parallel" |
| Low contrast | Dynamic range of the band | "Find better light" |
| Page curl | Baseline curvature | "Flatten the page" |
| No MRZ | Detector confidence low | "Show the photo page" |

Notes from the sources:

- Glare is the most damaging defect because a streak can turn one digit into another
  cleanly, so the recognizer is confident and wrong. Check digits plus multi-frame voting
  are the defense; prompting a tilt removes the cause.
- Higher resolution does not fix blur or glare. Sharpness and focus matter more than
  pixel count.

---

## 9. Android camera pipeline findings

- **CameraX ImageAnalysis** with `STRATEGY_KEEP_ONLY_LATEST` backpressure is the standard
  setup for real-time analysis.
- **Resolution.** CameraX silently lowers the analysis resolution when `ImageCapture` is
  bound alongside `Preview` and `ImageAnalysis`; one report shows 1440x1080 with
  analysis alone dropping to 1024x768 with all three bound. Bind only Preview and
  ImageAnalysis. Set a `ResolutionSelector` targeting 1920x1080 with a fallback rule
  (closest higher, then closest lower). Some budget devices default to 640x480, which is
  too low for MRZ; others advertise 1080p but deliver noisy, heavily compressed frames,
  so advertised size is not sufficient and device testing is required.
- **Autofocus** hunting is reported as the main cause of OCR failure on budget phones.
  Use continuous AF with a focus/metering point on the MRZ band, and consider locking
  focus once the band is found at a stable distance. Recommend a working distance in the
  UI (the band should fill most of the frame width).
- Process the **Y (luma) plane only** from `YUV_420_888`; MRZ is black on white and
  chroma adds nothing. Handle row and pixel stride correctly.
- Run detection on a downscaled copy and recognition on the full-resolution crop.
- Use GPU or NNAPI delegates with a CPU fallback, and benchmark thermal throttling over a
  60-second session on the slowest supported device.

---

## 10. Airline-specific considerations

- The MRZ carries the APIS core fields: names, nationality, document number, date of
  birth, expiry, sex, issuing state, document type. Map the result model directly onto
  these so integrators do not re-derive them.
- Reported common APIS data-quality errors are typos in names and document numbers,
  followed by expiry dates and nationality. These are exactly the fields the
  check-digit-and-voting pipeline protects. Names have no check digit except through the
  composite (which in TD3 does **not** cover line 1), so names rely entirely on
  multi-frame voting and should carry their own confidence.
- Expiry is assessed against **arrival**, not departure. The SDK should return the raw
  date; the validity rule belongs to the airline's business logic (Timatic or equivalent),
  but the result model should make that easy.
- The document subtype (second character of the document code) matters for acceptance
  rules; expose it.
- Scanning does not replace border control. Position the SDK output as "read with
  confidence X and check status Y", never as "document valid".

---

## 11. Metrics to track from day one

- **Exact-MRZ accuracy:** all 88 characters correct. The strict, all-or-nothing metric
  vendors use.
- **Per-field accuracy** (document number, DOB, expiry, nationality, names).
- **Check-digit-verified rate:** share of sessions ending with all check digits passing.
- **False accept rate:** sessions where all check digits passed but a field was wrong.
  This is the number an airline will care about most. Target as close to zero as
  possible; it is the reason not to over-correct toward passing checksums.
- **Time to result** (median and p95) and **abandonment rate** per device tier.
- **Per-hint frequency** (how often users are told to fix glare, distance, etc.), which
  tells you where capture UX is failing.

Test corpora: a private set of real captures across many issuing countries, lighting
conditions, and device tiers; MIDV-500 and MIDV-2020 as public regression sets; a
synthetic set with controlled degradation levels for sweep tests.

---

## 12. Recommended pipeline for this SDK

1. **Camera:** CameraX, Preview + ImageAnalysis only, 1080p target, luma plane, latest-frame
   backpressure, AF point on the band.
2. **Detector:** BlazeFace or YOLO-nano class TFLite model, 0.4 to 1 MB, quadrilateral
   output, two-pass ROI refinement. Trained on synthetic plus real.
3. **Quality gate:** blur, glare, size, cut-off, tilt, curl, contrast. Drives UI hints and
   decides whether to spend recognizer time on the frame.
4. **Rectifier:** homography warp of each line to a fixed-height canonical strip.
5. **Recognizer:** 37-class MRZ-specific model (start with segment-and-classify per cell;
   move to CRNN+CTC if segmentation errors show up on curved pages). Emits per-position
   probability distributions.
6. **Parser/validator:** pure Kotlin, ICAO 9303 TD3, field alphabets, all check digits,
   non-ISO country codes, field-aware alternative re-ranking, raw plus corrected output.
7. **Integrator:** per-position weighted voting with frame quality and per-character
   weights; structural-failure frames dropped; reset on document change.
8. **Stopping rule:** all check digits pass, stable for N frames, confidence margin met,
   hard timeout with flagged partial result.
9. **Result model:** APIS-shaped fields, document subtype, raw and corrected MRZ, per-field
   confidence and check status, diagnostic bundle with no personal data.

A pragmatic path: build stages 1, 4, 6, 7, 8 first with ML Kit's bundled offline
text recognizer as a stand-in for stages 2 and 5. That proves the pipeline, the parser,
and the UI end to end. Then replace detection and recognition with the dedicated models,
which is where the accuracy ceiling is lifted.

---

## Sources

Research papers and patents

- Hartl, Arth, Schmalstieg, "Real-time Detection and Recognition of Machine-Readable Zones with Mobile Devices", VISAPP 2015. <https://www.scitepress.org/papers/2015/52947/52947.pdf>
- "MRZ code extraction from visa and passport documents using convolutional neural networks", arXiv 2009.05489 / IJDAR 2021. <https://arxiv.org/abs/2009.05489>
- Gayer, Ershova, Arlazarov, "An accurate approach to real-time machine-readable zone detection with mobile devices" (YOLO-MRZ), IJDAR 2023. <https://dl.acm.org/doi/abs/10.1007/s10032-023-00435-w>
- "MobileMRZNet: Efficient and Lightweight MRZ Detection for Mobile Devices", 2024. <https://dergipark.org.tr/en/download/article-file/3646577>
- "Machine-Readable Zones Detection in Images Captured by Mobile Devices' Cameras", Pattern Recognition and Image Analysis 2020. <https://link.springer.com/article/10.1134/S105466182003013X>
- Petrova, Bulatov, Arlazarov, "Methods of weighted combination for text field recognition in a video stream", ICMV 2019. <https://www.spiedigitallibrary.org/conference-proceedings-of-spie/11433/114332L/Methods-of-weighted-combination-for-text-field-recognition-in-a/10.1117/12.2559378.short>
- Bulatov et al., "Weighted combination of per-frame recognition results for text recognition in a video stream". <https://www.researchgate.net/publication/349675847_Weighted_combination_of_per-frame_recognition_results_for_text_recognition_in_a_video_stream>
- Bulatov, Razumnyi, Arlazarov, "On optimal stopping strategies for text recognition in a video stream as an application of a monotone sequential decision model", IJDAR 2019. <https://www.researchgate.net/publication/334710274_On_optimal_stopping_strategies_for_text_recognition_in_a_video_stream_as_an_application_of_a_monotone_sequential_decision_model>
- Bulatov, Fedotova, Arlazarov, "Fast Approximate Modelling of the Next Combination Result for Stopping the Text Recognition in a Video", ICPR 2021. <https://arxiv.org/pdf/2008.02566>
- "Next integrated result modelling for stopping the text field recognition process in a video using a result model with per-character alternatives", ICMV 2019. <https://www.spiedigitallibrary.org/conference-proceedings-of-spie/11433/114332M/Next-integrated-result-modelling-for-stopping-the-text-field-recognition/10.1117/12.2559447.short>
- "Determining Optimal Frame Processing Strategies for Real-Time Document Recognition Systems", 2021. <https://link.springer.com/chapter/10.1007/978-3-030-86331-9_18>
- US Patent 11,640,720, text recognition in a video stream with per-character weighting. <https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11640720>
- US Patent 11,816,910, approximate modeling of next combined result for stopping. <https://patents.justia.com/patent/11816910>
- US Patent 12,469,291, neural-network prediction of the stopping moment. <https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12469291>
- US Patent 9,916,500, imaging travel documents in mobile applications (field-context MRZ correction). <https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9916500>
- "Generation method of synthetic training data for mobile OCR system", SPIE 2018. <https://www.researchgate.net/publication/324552516_Generation_method_of_synthetic_training_data_for_mobile_OCR_system>
- "Synthesizing Annotated Image and Video Data Using a Rendering-Based Pipeline for Improved License Plate Recognition", arXiv 2209.14448. <https://arxiv.org/pdf/2209.14448>
- MIDV-500 dataset paper, arXiv 1807.05786. <https://arxiv.org/pdf/1807.05786>
- MIDV-2020 dataset paper, arXiv 2107.00396. <https://arxiv.org/pdf/2107.00396>
- "Recognition of Passport MRZ Information Using Combined Neural Networks", Korea Society of Digital Industry and Information Management. <https://www.koreascience.kr/article/JAKO201908662573132.pdf>

Standards

- ICAO Doc 9303 Part 3, Specifications Common to all MRTDs (check digit algorithm, transliteration, codes). <https://icao.int/publications/Documents/9303_p3_cons_en.pdf>
- ICAO Doc 9303 Part 4, Specifications for Machine Readable Passports and other TD3 Size MRTDs. <https://www.icao.int/publications/documents/9303_p4_cons_en.pdf>

Vendor evaluations and engineering write-ups (self-reported, not peer reviewed)

- Smart Engines, "Problems of machine-readable zone recognition on mobile devices". <https://smartengines.medium.com/problems-of-machine-readable-zone-recognition-on-mobile-devices-8e70fe62a21a>
- Dynamsoft, "Evaluating MRZ Scanning Accuracy: Insights from Scanned and Photographed Passports". <https://www.dynamsoft.com/blog/insights/mrz-scanning-confusable-pairs-evaluation/>
- Dynamsoft, "How to Benchmark Passport MRZ Recognition Accuracy on MIDV-500". <https://www.dynamsoft.com/codepool/benchmark-mrz-recognition-midv500-dataset-python.html>
- Dynamsoft, "How to Build a Synthetic MRZ Dataset for OCR Training and Testing". <https://www.dynamsoft.com/codepool/synthetic-mrz-images-dataset.html>
- iApp passport OCR docs (check-digit-verified rate benchmark). <https://iapp.co.th/docs/ekyc/passport>
- Dissimilis.MrzScanner (per-frame failure hints API shape). <https://www.nuget.org/packages/Dissimilis.MrzScanner>
- Matil.ai, "Passport Machine Readable Zone: A Complete Guide". <https://matil.ai/en/blog/passport-machine-readable-zone>
- Didit, "MRZ Explained: Machine Readable Zone Technical Guide". <https://didit.me/blog/mrz-machine-readable-zone-technical-guide/>
- OCR Studio, "Mobile MRZ Scanning: Technology & Integration". <https://ocrstudio.ai/blog/mobile-mrz-scanning-technology-benefits-and-integration/>
- Zebra AI Data Capture CameraX guidance (ResolutionSelector, backpressure). <https://techdocs.zebra.com/ai-datacapture/3-0/camerax>
- CameraX developers group, analysis resolution limited when ImageCapture is bound. <https://groups.google.com/a/android.com/g/camerax-developers/c/_uYLo-1RmrU>
- "CameraX OCR on low-end phones" (resolution filter, focus lock). <https://docs.bswen.com/blog/2026-03-11-camerax-ocr-low-end-phones>

Open-source projects referenced

- jllarraz/AndroidPassportReader (ML Kit filler-character workaround). <https://github.com/jllarraz/AndroidPassportReader>
- alsenet-labs/mrz-scanner (small CNN trained on real vs synthetic OCR-B bitmaps). <https://github.com/alsenet-labs/mrz-scanner>
- Shreeshrii/tessdata_ocrb (Tesseract OCR-B fine-tune). <https://github.com/Shreeshrii/tessdata_ocrb>
- innr/emberfall PR 1 (synthetic TD3 generator with YOLO labels and check-digit tests). <https://github.com/innr/emberfall/pull/1>
- PassportEye. <https://github.com/gesmit/PassportEye>
- OpenMLKitOCR CRNN+CTC TFLite recognizers. <https://huggingface.co/0cve0/OpenMLKitOCR>

Airline context

- IATA Knowledge Hub, "What actually happens when a passenger's travel documents get checked". <https://www.iata.org/en/publications/newsletters/iata-knowledge-hub/what-actually-happens-when-a-passengers-travel-documents-get-checked/>
- IATA CAWG, "Guidelines for collaboratively improving API data quality", 2021. <https://www.iata.org/contentassets/67e015cf3db1410392cd5b5bb5961a16/iata-cawg-guidelines-for-collaboratively-improving-api-dq_2021.pdf>
- Passenger Terminal Today on common APIS errors. <https://www.passengerterminaltoday.com/?p=21069>

---

## 13. How commercial vendors (Regula, Smart Engines, Microblink) reach near-100%

None of these vendors publish a mobile MRZ accuracy benchmark with methodology. What they
do publish, in product pages, release notes, patents and papers, shows that the result
comes from a stack of decisions around the OCR model, not from a better OCR model alone.

1. **Document identification before reading.** Regula matches the capture against a
   template database (14,000 templates in 2024, 17,000+ by 2026, from around 250
   countries). Once the document is identified, the MRZ location, the OCR-B variant,
   the visual-zone layout and the expected field formats are all known. Detection
   becomes verification of a known layout, which is far easier than open search.
2. **Cross-zone verification.** Regula reads the visual inspection zone (VIZ) with
   template-guided OCR and compares it field by field with the MRZ, reporting
   mismatches. Microblink does the same across barcode, MRZ and VIZ, with a stated
   preference order of barcode, then MRZ, then VIZ. Two independent reads of document
   number, date of birth and expiry agreeing is what pushes residual error toward zero.
3. **Interframe integration and anytime algorithms.** Smart Engines names "computational
   geometry, anytime algorithms and interframe integration" as the key components of
   MRZ recognition in a video stream. Microblink markets "multi-frame analysis". This is
   the weighted per-character voting and stopping rule described in sections 6 and 7.
4. **Small proprietary models trained on real documents.** Smart Engines reports 8-bit
   and 4-bit integer networks and an MRZ detector running in 16 ms on an iPhone SE 2020.
   Regula is a forensic-equipment company with physical document collections, so its
   training and regression sets are real documents across issuers, print runs and
   wear, not just synthetic renders. The data is the moat more than the architecture.
5. **Per-issuer parsers and font handling.** Regula release notes repeatedly list
   "updated country parsers", "improved reading of MRZ printed with non-standard
   fonts" and fixes for check-digit handling. Years of issuer-specific quirks are
   encoded in the parser layer.
6. **Reject rather than guess.** Results are validated against ICAO 9303 and ISO 18013
   and returned with status flags. A read that cannot be verified is reported as such
   instead of returned as data. The "almost 100%" figure is accuracy on accepted reads;
   the reject rate is not published.
7. **Capture quality control and UX.** Blur detection, glare handling, document-in-frame
   guidance and auto-capture mean the recognizer mostly sees frames worth reading.
8. **Hardware context.** Regula's strongest accuracy claims are for desktop readers with
   fixed optics and controlled white, infrared and ultraviolet lighting. Mobile is the
   harder problem, and the one public mobile-ish data point is Dynamsoft's own
   MIDV-2020 photographed-passport result going from 71% to about 95% between builds.

Gap checklist for an existing scanner that is "not great":

- Generic OCR (ML Kit, Tesseract) instead of a 37-class MRZ recognizer.
- Single-frame decisions instead of weighted multi-frame integration.
- No rectification before recognition.
- No per-position probabilities, so no field-aware confusable correction.
- Stops on the first checksum pass, or has no stopping rule.
- No cross-check against the VIZ.
- No capture-quality gate, so blurred and glared frames reach the recognizer.
- Analysis resolution silently lowered by CameraX use-case binding.
- No per-issuer regression corpus, so fixes regress.

Additional sources for this section

- Regula Document Reader SDK product page (ICAO 9303 and ISO 18013 validation, 17,000+ templates). <https://regulaforensics.com/products/document-reader-sdk/>
- Regula, "MRZ Verification: Its Role in ID Verification" (combine MRZ with VIZ, barcode, chip checks). <https://regulaforensics.com/blog/machine-readable-zone/>
- Regula, document type identification technology. <https://regulaforensics.com/explore/technologies/document-type-identification/>
- Regula template database expansion to 14,000 (2024). <https://regulaforensics.com/news/regula-expands-worlds-largest-id-template-database/>
- Regula Document Reader SDK release notes 7.5 (non-standard MRZ fonts, country parsers). <https://docs.regulaforensics.com/develop/doc-reader-sdk/release-notes/7-5/>
- Regula Document Reader SDK release notes 8.3 (quick MRZ processing in video mode). <https://docs.regulaforensics.com/develop/doc-reader-sdk/release-notes/8-3/>
- Smart Engines MRZ Scanning SDK (interframe integration, anytime algorithms, 8-bit networks). <https://smartengines.com/ocr-engines/code-engine/mrz-reader/>
- Smart Engines 2023 scientific activity report (16 ms MRZ detection). <https://smartengines.com/news-events/smart-engines-reported-on-the-scientific-activity-in-the-ai-field-in-2023/>
- Smart ID Engine technical reference (integer pipeline, 8-bit and 4-bit networks). <https://smartengines.com/wp-content/uploads/2021/04/smart-id-engine-datasheet-eng.pdf>
- Smart Engines, data augmentation for printed character recognition. <https://smartengines.medium.com/data-augmentation-for-neural-network-training-example-for-printed-characters-recognition-8e273bbb6c66>
- Microblink BlinkID product page (multi-frame analysis, cross-zone checks). <https://microblink.com/en/products/blinkID>
- Microblink, "The Nuts and Bolts of the BlinkID v5" (ML pipeline, blur detection). <https://microblink.com/resources/blog/the-nuts-and-bolts-of-the-blinkid-v5/>
- BlinkID Android SDK (barcode, MRZ, VIZ preference order; 1080p preview). <https://github.com/microblink/blinkid-android>
- Dynamsoft, "Unlock Faster, More Accurate ID Verification with the Dynamsoft MRZ SDK". <https://www.dynamsoft.com/blog/insights/passport-id-mrz-sdk-accuracy>
- Scanbot, "How to scan passports with a mobile device". <https://scanbot.io/blog/how-to-scan-passports/>
