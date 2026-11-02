# Passport MRZ Scanner SDK: Architecture and Module Structure

**Scope:** Android, TD3 passports only, MRZ read only (no NFC), SDK-owned camera UI, fully
offline, airline check-in and boarding use.

**Date:** 2026-11-02

This document defines the SDK's layers, Gradle modules, public API, runtime data flow,
threading model, packaging, and test strategy. It builds on the techniques in
`docs/research/01-mrz-scanning-techniques.md`.

The package prefix `com.airline.mrz` is a placeholder. Replace it with the real
organisation's reverse domain before the first publish; it is part of the public API.

---

## 1. Design goals and the constraints they impose

| Goal | Consequence for the architecture |
|---|---|
| Vendor-grade accuracy | Dedicated detector and recognizer, multi-frame integration, stopping rule, VIZ cross-check. The recognition core must expose per-position probabilities, not strings. |
| Fully offline | All models bundled in the AAR. No network permission requested. Nothing in the SDK may open a socket. |
| SDK owns the UI | A drop-in scanning screen is the primary entry point, but it sits on a headless engine so the airline can also build its own UI later. |
| Airline data flows into APIS | Result model is APIS-shaped, carries raw and corrected MRZ, per-field confidence and check status, and never silently "fixes" data. |
| Low-end fleet devices | Every heavy stage is replaceable behind an interface, the per-frame budget is explicit, and the pipeline degrades to "skip frames" rather than "lag". |
| Privacy | No frames or MRZ text touch disk. Buffers are zeroed. Diagnostics carry no personal data. |
| Long-lived product | Pure-Kotlin core that is testable on the JVM, semantic versioning, binary-compatibility checks, models versioned independently of code. |

---

## 2. Layered architecture

```
+-------------------------------------------------------------------+
|  mrz-ui            Compose screen, overlay, hints, theming, i18n   |  Android
+-------------------------------------------------------------------+
|  mrz-sdk           PassportScanner facade, config, result,        |  Android
|                    ScanSession, Activity contract, licensing       |
+-------------------------------------------------------------------+
|  mrz-camera        CameraX frame source, focus/torch, orientation |  Android
+-------------------------------------------------------------------+
|  mrz-engine-tflite TFLite detector + recognizer + VIZ reader      |  Android
|  mrz-engine-mlkit  ML Kit stand-in recognizer (dev/prototype)     |  Android
+-------------------------------------------------------------------+
|  mrz-engine-api    Engine interfaces, frame + image types,        |  Android (no UI)
|                    rectifier, quality metrics (pure image math)    |
+-------------------------------------------------------------------+
|  mrz-core          ICAO 9303 TD3 parser, check digits, field      |  Pure Kotlin/JVM
|                    alphabets, country codes, correction,           |
|                    frame integrator, stopping rule, result model   |
+-------------------------------------------------------------------+
```

Dependency direction is strictly downward. `mrz-core` has no Android dependency at all.
`mrz-engine-api` depends on `mrz-core` only. Implementations (`-tflite`, `-mlkit`) are
swappable and the facade picks one at runtime. `mrz-ui` depends on `mrz-sdk`, never the
other way round, so a headless integration can exclude the UI module entirely.

---

## 3. Gradle module layout

```
passport-mrz-sdk/
├── settings.gradle.kts
├── build.gradle.kts                  # plugins, versions via gradle/libs.versions.toml
├── gradle/libs.versions.toml
│
├── mrz-core/                         # Kotlin JVM library (no Android plugin)
├── mrz-engine-api/                   # Android library
├── mrz-engine-tflite/                # Android library, bundles models in assets
├── mrz-engine-mlkit/                 # Android library, optional, dev only
├── mrz-camera/                       # Android library
├── mrz-sdk/                          # Android library: the published facade AAR
├── mrz-ui/                           # Android library: Compose UI
│
├── sample-app/                       # Reference integration, not published
├── benchmark/                        # Macrobenchmark + on-device accuracy harness
│
├── tools/
│   ├── synth/                        # Synthetic TD3 data generator (Python)
│   ├── training/                     # Model training + TFLite export (Python)
│   ├── eval/                         # JVM accuracy harness over labelled corpora
│   └── corpus/                       # Corpus manifests only; images live outside git
│
└── docs/
    ├── research/
    ├── architecture/
    └── api/                          # Dokka output, integration guide
```

Published artifacts (Maven coordinates, placeholder group):

| Artifact | Contents | Who depends on it |
|---|---|---|
| `com.airline.mrz:mrz-sdk` | Facade, session, config, result; transitively pulls core, engine-api, engine-tflite, camera | Every integrator |
| `com.airline.mrz:mrz-ui` | Compose scanning screen | Integrators using the SDK UI (the default) |
| `com.airline.mrz:mrz-core` | Parser and integrator only | Backend teams validating MRZ strings on the JVM |

`mrz-engine-mlkit` is never published. It exists so the pipeline, parser and UI can be
built and demoed before the custom models are ready.

---

## 4. Module responsibilities and package structure

### 4.1 `mrz-core` (pure Kotlin, JVM)

The only module that understands what an MRZ *means*. No image types, no Android.

```
com.airline.mrz.core
├── model/
│   ├── MrzLine.kt              value class over a 44-char string
│   ├── Td3Mrz.kt               two lines, typed field accessors
│   ├── PassportData.kt         parsed, APIS-shaped result (see §6)
│   ├── FieldSpec.kt            position ranges, alphabet, check-digit coverage
│   └── CountryCodes.kt         ICAO list incl. D, GBD, GBN, GBO, UNO, XXA, RKS, ...
├── parse/
│   ├── Td3Parser.kt            string -> Td3Mrz or structured error
│   ├── CheckDigit.kt           7-3-1 mod 10, '<' = 0
│   ├── DateParser.kt           YYMMDD, '<' digits, century pivot per field
│   └── NameParser.kt           primary/secondary split, truncation flag
├── recognize/
│   ├── CharDistribution.kt     37-way probability vector for one position
│   ├── LineHypothesis.kt       44 distributions + frame weight
│   └── FieldAwareCorrector.kt  alphabet-constrained re-ranking + check-digit confirm
├── integrate/
│   ├── FrameIntegrator.kt      weighted per-position accumulation, reset logic
│   ├── StabilityTracker.kt     consecutive-agreement counter
│   └── StoppingRule.kt         checks pass + stable N + margin + timeout
└── result/
    ├── FieldConfidence.kt
    ├── CheckStatus.kt          PASS / FAIL / NOT_PRESENT per check digit
    └── MrzReadOutcome.kt       Verified / Unverified(partial) / Failed(reason)
```

Key design rule: **`FieldAwareCorrector` never mutates the raw read.** It produces a
second string plus a list of `(position, from, to, probability)` corrections. Both strings
travel to the result.

### 4.2 `mrz-engine-api` (Android library, no UI, no models)

Interfaces and pure image math that every engine implementation shares.

```
com.airline.mrz.engine
├── Frame.kt                    luma plane + width/height/rowStride + rotation + timestamp
├── MrzDetector.kt              interface: Frame -> Detection(quad, confidence)?
├── MrzRecognizer.kt            interface: RectifiedLines -> List<LineHypothesis>
├── VizReader.kt                interface: Frame + Detection -> VizFields? (doc no, DOB, expiry)
├── MrzEngine.kt                composes detector + recognizer + optional VizReader
├── image/
│   ├── LumaImage.kt            immutable view over a ByteBuffer, stride-aware
│   ├── Rectifier.kt            quad -> fixed-height line strips (homography, bilinear)
│   ├── Downscaler.kt           box filter for the detector input
│   └── Quality.kt              blur (Laplacian var), glare area, contrast, band height,
│                               edge contact, tilt, baseline curvature
└── QualityGate.kt              Quality -> GateDecision(accept | reject(Hint))
```

`Frame` wraps the luma plane only. Chroma planes are never copied out of the camera
buffer.

### 4.3 `mrz-engine-tflite` (Android library)

The production engine. Owns the models and the interpreter lifecycle.

```
com.airline.mrz.engine.tflite
├── TfliteMrzDetector.kt        BlazeFace/YOLO-nano class, quad output, two-pass ROI
├── TfliteMrzRecognizer.kt      37-class per-cell CNN (v1), CRNN+CTC (v2) behind same interface
├── TfliteVizReader.kt          line detector + same recognizer on VIZ doc-no/DOB/expiry
├── Delegates.kt                GPU/NNAPI probe at init, CPU fallback, cached decision
├── ModelRegistry.kt            asset names, versions, SHA-256, input/output specs
└── assets/
    ├── mrz_detector_v1.tflite
    ├── mrz_recognizer_v1.tflite
    ├── viz_line_detector_v1.tflite
    └── models.json             manifest with versions + hashes
```

Models are verified against the manifest hash at load. A mismatch is a hard init
failure, not a silent fallback.

### 4.4 `mrz-engine-mlkit` (Android library, dev only)

Implements `MrzRecognizer` with ML Kit's bundled on-device text recognizer, mapping its
string output to one-hot `CharDistribution`s. Zero accuracy ambitions. Lets the team
build and demo the full loop on day one and gives a baseline for the eval harness.

### 4.5 `mrz-camera` (Android library)

```
com.airline.mrz.camera
├── FrameSource.kt              interface: Flow<Frame>, start/stop, focus/torch control
├── CameraXFrameSource.kt       Preview + ImageAnalysis only; 1080p ResolutionSelector
│                               with fallback; KEEP_ONLY_LATEST; single analysis executor
├── FocusController.kt          AF/AE metering point on the MRZ band, optional lock
├── Orientation.kt              sensor-to-display rotation, landscape passport guidance
└── DeviceQuirks.kt             per-model overrides (min resolution, disable GPU delegate)
```

`FrameSource` is an interface so tests and the eval harness can feed recorded frames.

### 4.6 `mrz-sdk` (Android library, the facade)

```
com.airline.mrz
├── PassportScanner.kt          entry point: create(context, config)
├── ScanConfig.kt               see §7
├── ScanResult.kt               sealed: Success / Unverified / Failed / Cancelled
├── ScanSession.kt              headless: consumes FrameSource, emits ScanState, returns result
├── ScanState.kt                sealed UI state: Searching / Aligning(hint) / Reading(progress) / Done
├── contract/
│   └── ScanPassportContract.kt ActivityResultContract<ScanConfig, ScanResult>
├── license/
│   └── LicenseValidator.kt     offline signed license file, app-id bound, expiry
├── diagnostics/
│   └── DiagnosticBundle.kt     device, delegate, timings, hint counts; no PII
└── internal/
    ├── Pipeline.kt             the per-frame orchestration (see §5)
    └── SecureBuffers.kt        zeroing helpers for luma buffers and char arrays
```

### 4.7 `mrz-ui` (Android library, Compose)

```
com.airline.mrz.ui
├── MrzScannerScreen.kt         @Composable (config, onResult)
├── MrzScannerActivity.kt       hosts the screen; registered via manifest merge
├── overlay/
│   ├── ViewfinderOverlay.kt    passport-shaped guide, MRZ band highlight, progress
│   └── HintBanner.kt           maps Hint -> localized string + icon
├── theme/
│   └── MrzTheme.kt             colors, typography, overridable via ScanConfig.theme
└── res/values-*/strings.xml    hints localized; integrator can override by resource name
```

A classic `View`-based wrapper (`MrzScannerView`) is a thin adapter over the composable
and lives in the same module.

---

## 5. Runtime: the per-frame pipeline

```
CameraXFrameSource ──Frame──▶ Pipeline.process(frame)
                                  │
                                  ├─ 1. Downscaler ─▶ MrzDetector ─▶ Detection? ── none ──▶ state=Searching
                                  │
                                  ├─ 2. Quality.measure(frame, quad) ─▶ QualityGate
                                  │        reject ──▶ state=Aligning(hint); frame dropped
                                  │
                                  ├─ 3. Rectifier.warp(frame, quad) ─▶ 2 line strips
                                  │
                                  ├─ 4. MrzRecognizer ─▶ 2 × LineHypothesis (44 × 37 probs)
                                  │
                                  ├─ 5. structural sanity (length, 'P' at pos 1, fillers)
                                  │        fail ──▶ frame dropped, not voted
                                  │
                                  ├─ 6. FrameIntegrator.add(hypotheses, frameWeight, charWeights)
                                  │
                                  ├─ 7. integrated ──▶ Td3Parser + FieldAwareCorrector
                                  │        ──▶ CheckStatus per check digit
                                  │
                                  ├─ 8. every K frames (or once checks pass): VizReader on
                                  │     doc-no / DOB / expiry ──▶ cross-check vs MRZ
                                  │
                                  └─ 9. StoppingRule.evaluate(...)
                                           continue ──▶ state=Reading(progress)
                                           stop     ──▶ ScanResult, FrameSource.stop()
```

Budget on the slowest supported device (target, to be measured): detector under 15 ms,
quality under 5 ms, rectify under 5 ms, recognizer under 40 ms, parse and integrate
under 2 ms. If a frame cannot start within its budget the analyzer drops it; the pipeline
never queues.

### Session state machine

```
            start()
   Idle ────────────▶ Searching ──detection──▶ Aligning ──gate ok──▶ Reading
                        ▲                        │  ▲                 │
                        │   lost detection       │  │ gate reject     │ stopping rule
                        └────────────────────────┘  └─────────────────┤
                                                                      ▼
                       cancel() / timeout                           Done(result)
   any state ─────────────────────────────────▶ Done(Cancelled | Unverified | Failed)
```

`ScanState` is exposed as a `StateFlow` so the UI module and any custom UI render the
same states.

### Stopping rule (from `mrz-core`)

Stop with `Success` when all of the following hold:

1. Integrated MRZ parses and every check digit present is `PASS`.
2. The integrated string has been identical for `stableFrames` consecutive voted frames
   (default 3).
3. Minimum per-position margin (top-1 minus top-2 probability) across all 88 positions
   exceeds `minMargin` (default 0.25).
4. If VIZ cross-check is enabled and a VIZ read was obtained, document number, DOB and
   expiry agree. A disagreement blocks `Success` and continues reading until timeout.

On `timeoutMs` (default 15,000): return `Unverified` with the best integrated result,
its check statuses and confidences, only if `allowUnverifiedResult` is true; otherwise
`Failed(Timeout, lastHint)`.

---

## 6. Result model

```kotlin
sealed interface ScanResult {
    data class Success(val passport: PassportData, val read: MrzRead, val diagnostics: DiagnosticBundle) : ScanResult
    data class Unverified(val passport: PassportData?, val read: MrzRead, val diagnostics: DiagnosticBundle) : ScanResult
    data class Failed(val reason: FailureReason, val diagnostics: DiagnosticBundle) : ScanResult
    object Cancelled : ScanResult
}

data class PassportData(              // APIS-shaped
    val documentCode: String,         // "P" + subtype char, e.g. "P<", "PD"
    val issuingState: String,         // ICAO 3-letter
    val primaryIdentifier: String,    // surname(s)
    val secondaryIdentifier: String,  // given names
    val nameTruncated: Boolean,
    val documentNumber: String,
    val nationality: String,
    val dateOfBirth: LocalDate?,      // null if issuer used '<' digits
    val sex: Sex,                     // M, F, UNSPECIFIED
    val dateOfExpiry: LocalDate,
    val optionalData: String,         // personal number, may be empty
    val fieldConfidence: Map<Field, Float>,
)

data class MrzRead(
    val rawLine1: String, val rawLine2: String,          // as recognized
    val correctedLine1: String, val correctedLine2: String,
    val corrections: List<Correction>,                   // position, from, to, probability
    val checks: Map<CheckDigit, CheckStatus>,            // DOC_NO, DOB, EXPIRY, OPTIONAL, COMPOSITE
    val vizCrossCheck: VizCrossCheck?,                   // AGREES / DISAGREES(fields) / NOT_READ
    val framesVoted: Int, val durationMs: Long,
)
```

Rules: the SDK returns dates, not validity verdicts. Expiry versus arrival date is the
airline's business rule. `Success` means "read verified", never "document valid".

---

## 7. Configuration

```kotlin
data class ScanConfig(
    val stableFrames: Int = 3,
    val minMargin: Float = 0.25f,
    val timeoutMs: Long = 15_000,
    val allowUnverifiedResult: Boolean = false,
    val vizCrossCheck: Boolean = true,
    val torch: TorchMode = TorchMode.AUTO,       // OFF, AUTO (on when contrast low), ON
    val secureScreen: Boolean = true,            // FLAG_SECURE on the scanner Activity
    val haptics: Boolean = true,
    val theme: MrzTheme = MrzTheme.Default,
    val locale: Locale? = null,                  // null = device locale
    val diagnosticsLevel: DiagnosticsLevel = DiagnosticsLevel.BASIC,
)
```

Everything else (resolution, delegates, thresholds inside the gate) is internal and
tuned per device via `DeviceQuirks`, not exposed. Exposing it would make the accuracy
guarantee impossible to support.

---

## 8. Public API: three tiers

```kotlin
// Tier 1: drop-in (default for the airline app)
val launcher = registerForActivityResult(ScanPassportContract()) { result -> ... }
launcher.launch(ScanConfig())

// Tier 2: embed the screen in your own navigation (Compose)
MrzScannerScreen(config = ScanConfig(), onResult = { result -> ... })

// Tier 3: headless, bring your own UI and camera
val scanner = PassportScanner.create(context, config)
val session = scanner.startSession(frameSource = CameraXFrameSource(lifecycleOwner, previewView))
session.state.collect { state -> render(state) }
val result = session.await()
```

Java interop: `@JvmOverloads` on constructors, a `ScanSessionCallback` adapter in place
of `Flow`, `ListenableFuture` adapter for `await()`. Kotlin explicit API mode is on in
every published module and `binary-compatibility-validator` runs in CI.

`PassportScanner.create` is the single place models are loaded and delegates probed.
It is cheap to keep alive and should be created once per process (Hilt/Koin singleton
in the sample app, but the SDK does not depend on any DI framework).

---

## 9. Threading and lifecycle

- One single-threaded `Executor` for `ImageAnalysis`. All of steps 1 to 9 run on it,
  sequentially, per frame. No frame is ever in flight on two threads.
- TFLite interpreters are created on that executor and are never shared.
- `ScanSession` is a coroutine scope tied to the host lifecycle. Cancelling the scope
  stops the camera, closes interpreters, zeroes buffers.
- `ScanState` is a `StateFlow` (conflated by nature) so a slow UI never backpressures
  the pipeline.
- Configuration change: the `MrzScannerActivity` is `configChanges`-locked to portrait
  with the passport guidance drawn landscape; a headless session survives rotation
  because it owns no views.
- Process death during a scan is a cancel. Nothing is persisted to restore.

---

## 10. Model packaging and versioning

- Models live in `mrz-engine-tflite/src/main/assets/` and are listed in `models.json`
  with version and SHA-256. `ModelRegistry` validates at load.
- Model versions are independent of SDK version. The SDK version bumps when the public
  API or behaviour changes; the model version bumps when a retrain ships. The
  `DiagnosticBundle` records both.
- Size budget: detector 0.5 to 1 MB, recognizer 1 to 3 MB, VIZ line detector about
  1 MB, TFLite runtime about 2 MB (use `tensorflow-lite` with `-select-tf-ops` excluded).
  Target under 8 MB added to the integrator's AAB.
- GPU delegate is an optional dependency the integrator opts into; NNAPI is probed and
  denied by `DeviceQuirks` on known-bad SoCs. CPU with XNNPACK is the floor.
- ABIs: `arm64-v8a` and `armeabi-v7a` for production, `x86_64` for emulators and CI.
  16 KB page alignment on all native libs.

---

## 11. Security and privacy controls (where they live)

| Control | Module |
|---|---|
| No `INTERNET` permission; manifest lint rule fails the build if one appears | `mrz-sdk` |
| Luma buffers are pooled and zeroed on release; MRZ strings held as `CharArray` until result construction | `mrz-sdk/internal/SecureBuffers` |
| `FLAG_SECURE` on scanner Activity (configurable) | `mrz-ui` |
| No logging of MRZ content at any level; a lint rule blocks `Log.*` in `mrz-core` and the pipeline | all |
| Diagnostics schema has no free-text fields | `mrz-sdk/diagnostics` |
| License file signed (Ed25519), bound to applicationId, verified offline | `mrz-sdk/license` |
| Consumer R8 rules shipped; public API kept, internals obfuscated | each published module |
| Dependency pinning via version catalog; SBOM generated in CI | root |

---

## 12. Testing strategy per module

| Module | Tests | Runs on |
|---|---|---|
| `mrz-core` | Parser corpus (hundreds of real-shape MRZs incl. non-ISO codes, filler dates, truncated names); check-digit vectors from ICAO 9303 worked examples; corrector property tests (never changes a passing field; never produces an out-of-alphabet char); integrator and stopping-rule simulations with synthetic noisy hypotheses | JVM, every PR |
| `mrz-engine-api` | Rectifier against known homographies; quality metrics against degraded synthetic crops | JVM (Robolectric for Bitmap-free paths), every PR |
| `mrz-engine-tflite` | Model I/O shape contract; golden outputs on 50 fixed crops; delegate fallback | Instrumented, nightly |
| `mrz-camera` | Resolution selection on recorded `CameraCharacteristics`; stride handling | Robolectric + instrumented |
| `mrz-sdk` | Full pipeline over recorded frame sequences via a fake `FrameSource`; state machine; timeout and cancel; buffer zeroing | JVM with engine-mlkit or fake engine |
| `mrz-ui` | Compose screenshot tests for each state and hint; accessibility | Instrumented |
| `benchmark` | Time-to-result and frame latency on the device tier list; thermal 60 s run | Physical device lab, nightly |
| `tools/eval` | Exact-MRZ, per-field, check-verified rate, **false accept rate** over the private corpus and MIDV sets; regression gate | JVM, nightly and on model change |

---

## 13. Build and release

- Kotlin 2.x, AGP 8.x, `minSdk 24`, `compileSdk` current. Compose BOM pinned.
- `explicitApi()` and `binary-compatibility-validator` in every published module.
- Dokka for API docs, published with each release.
- Maven publishing to the airline's internal repository; artifacts signed.
- Semantic versioning. Deprecations survive two minor versions.
- CI stages: lint + manifest/network rule, JVM tests, Robolectric, instrumented on
  emulator (x86_64), nightly device lab and eval harness with accuracy gates.

---

## 14. Build order

1. `mrz-core`: parser, check digits, country codes, result model, with the test corpus.
   Shippable on its own to backend teams.
2. `mrz-engine-api` + `mrz-engine-mlkit` + `mrz-camera` + `mrz-sdk` pipeline + `mrz-ui`:
   the whole loop running end to end on ML Kit, with integrator and stopping rule live.
3. `tools/synth` + `tools/eval`: generate data, establish the baseline numbers on ML Kit.
4. `mrz-engine-tflite` detector, then recognizer, measured against the baseline.
5. VIZ cross-check.
6. Device lab, `DeviceQuirks`, benchmark gates, licensing, release pipeline.
