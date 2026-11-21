package com.airline.mrz

import java.io.Serializable
import java.util.Locale

public enum class TorchMode { OFF, AUTO, ON }

public enum class DiagnosticsLevel { NONE, BASIC, TIMINGS }

/** Which engine backs the session. Production integrations use [TFLITE]. */
public enum class EngineKind {
    /** Dedicated models from the model manifest. */
    TFLITE,

    /** ML Kit stand-in; only available in debug builds of the SDK. */
    MLKIT_DEV,
}

/**
 * Integrator-facing configuration. Everything else (resolution, delegates, gate
 * thresholds) is internal and tuned per device, because exposing it would make the
 * accuracy guarantee impossible to support.
 */
public data class ScanConfig @JvmOverloads constructor(
    /** Consecutive voted frames the corrected MRZ must stay identical for. */
    val stableFrames: Int = 3,
    /** Minimum top-two probability margin at every one of the 88 cells. */
    val minMargin: Float = 0.25f,
    val timeoutMs: Long = 15_000,
    /** On timeout, return the best unverified read instead of a failure. */
    val allowUnverifiedResult: Boolean = false,
    /** Cross-check document number, birth and expiry dates against the printed visual zone. */
    val vizCrossCheck: Boolean = true,
    val torch: TorchMode = TorchMode.AUTO,
    /** Set FLAG_SECURE on the scanner screen so it cannot be screenshotted or recorded. */
    val secureScreen: Boolean = true,
    val haptics: Boolean = true,
    /** Null means the device locale. */
    val localeTag: String? = null,
    val diagnosticsLevel: DiagnosticsLevel = DiagnosticsLevel.BASIC,
    val engine: EngineKind = EngineKind.TFLITE,
) : Serializable {
    public val locale: Locale? get() = localeTag?.let { Locale.forLanguageTag(it) }

    init {
        require(stableFrames >= 1)
        require(minMargin in 0f..1f)
        require(timeoutMs in 1_000..120_000) { "timeout must be between 1 and 120 seconds" }
    }

    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}
