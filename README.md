# Passport MRZ Scanner SDK (Android)

An offline Android SDK that reads the machine readable zone of TD3 passports from the
phone camera, built for airline check-in and boarding. It is designed around the
accuracy techniques commercial vendors use: a dedicated detector and recognizer that
emit per-character probabilities, weighted multi-frame integration, ICAO 9303
check-digit gating with field-aware correction, a conservative stopping rule, and a
visual-zone cross-check.

Design documents:

- `docs/research/01-mrz-scanning-techniques.md`: what the literature and vendors do.
- `docs/architecture/01-sdk-architecture.md`: modules, API, data flow.
- `docs/architecture/02-accuracy-strategy.md`: every decision, threshold and gate.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `mrz-core` | Kotlin/JVM | TD3 parser, check digits, country codes, per-cell distributions, structural filter, field-aware corrector, frame integrator, stopping rule, result model |
| `mrz-engine-api` | Kotlin/JVM | Engine interfaces, luma image math, homography rectifier, quality metrics and gate, heuristic band detector, the per-frame pipeline |
| `mrz-engine-tflite` | Android | Production detector, recognizer and visual-zone reader on TensorFlow Lite; model manifest with hash verification |
| `mrz-engine-mlkit` | Android, debug only | ML Kit stand-in recognizer so the whole loop runs before the models exist |
| `mrz-camera` | Android | CameraX frame source (Preview + ImageAnalysis only, 1080p, keep-latest), focus controller, device quirks |
| `mrz-sdk` | Android | `PassportScanner`, `ScanSession`, `ScanConfig`, `ScanResult`, Activity result contract, offline license check, diagnostics |
| `mrz-ui` | Android | Compose scanning screen, viewfinder overlay, localised hints, hosting Activity |
| `sample-app` | Android | Reference integration |
| `tools/eval` | Kotlin/JVM | Release-gate metrics over labelled results; fails on any false accept |
| `tools/synth` | Python | Synthetic TD3 generator and renderer |

## Building

The JVM modules build and test on any machine with a JDK 17 or newer:

```
./gradlew build
```

The Android modules are configured only when an Android SDK is found (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` or `local.properties`). Open the project in Android Studio or set
one of those and run:

```
./gradlew :mrz-sdk:check :sample-app:assembleDebug
```

## Integrating

```kotlin
// Once, at startup (loads models, probes accelerators):
MrzScannerHost.install(PassportScanner.create(context, ScanConfig(), licenseJson))

// Drop-in screen:
val launch = registerForActivityResult(ScanPassportContract()) { result ->
    when (result) {
        is ScanResult.Success -> submit(result.passport)   // verified read
        is ScanResult.Unverified -> review(result)         // only if allowUnverifiedResult
        is ScanResult.Failed, ScanResult.Cancelled -> retry()
    }
}
launch.launch(ScanConfig())
```

`ScanResult.Success` means the read was verified, never that the document is valid.
Expiry and acceptance rules stay in the airline's own logic.

## Status

- JVM core, engine math, pipeline and eval harness: implemented with unit tests.
- Android modules: implemented, not yet compiled in CI (requires the Android SDK).
- TFLite models: not yet trained. Until they exist, `EngineKind.MLKIT_DEV` runs the
  full loop on ML Kit in debug builds. See `tools/training/README.md`.
- The package prefix `com.airline.mrz` and the license public key are placeholders.
