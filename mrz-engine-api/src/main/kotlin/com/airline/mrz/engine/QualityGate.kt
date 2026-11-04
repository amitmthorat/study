package com.airline.mrz.engine

import com.airline.mrz.engine.image.QualityMetrics

/** What the UI should tell the user when a frame is rejected. First failing check wins. */
public enum class Hint {
    NO_MRZ,
    HOLD_STILL,
    AVOID_GLARE,
    MOVE_CLOSER,
    FIT_WHOLE_PASSPORT,
    HOLD_FLAT,
    HOLD_PARALLEL,
    FLATTEN_PAGE,
    MORE_LIGHT,
}

/**
 * Decides whether a frame is worth recognizer time. Thresholds are starting points
 * from the accuracy strategy and are meant to be re-tuned on the capture corpus; they
 * are grouped here so tuning touches one place.
 */
public class QualityGate(public val thresholds: Thresholds = Thresholds()) {

    public data class Thresholds(
        /** Laplacian variance below which the band is considered blurred. */
        val minSharpness: Float = 60f,
        /** Sharpness at which the frame weight saturates to 1. */
        val sharpnessScale: Float = 400f,
        val maxGlareFraction: Float = 0.02f,
        val minBandHeightPx: Float = 44f,
        val maxTiltDegrees: Float = 12f,
        val perspectiveRatioRange: ClosedFloatingPointRange<Float> = 0.85f..1.15f,
        val maxCurlPer100Px: Float = 0.8f,
        val minContrast: Int = 60,
        /** Stricter sharpness bar used when the device cannot recognise every frame. */
        val strictSharpnessMultiplier: Float = 1.5f,
    )

    public sealed interface Decision {
        public data class Accept(val frameWeight: Float) : Decision
        public data class Reject(val hint: Hint) : Decision
    }

    public fun evaluate(m: QualityMetrics, strict: Boolean = false): Decision {
        val t = thresholds
        val minSharp = if (strict) t.minSharpness * t.strictSharpnessMultiplier else t.minSharpness
        return when {
            m.sharpness < minSharp -> Decision.Reject(Hint.HOLD_STILL)
            m.glareFraction > t.maxGlareFraction -> Decision.Reject(Hint.AVOID_GLARE)
            m.bandHeightPx < t.minBandHeightPx -> Decision.Reject(Hint.MOVE_CLOSER)
            m.touchesEdge -> Decision.Reject(Hint.FIT_WHOLE_PASSPORT)
            m.tiltDegrees > t.maxTiltDegrees -> Decision.Reject(Hint.HOLD_FLAT)
            m.perspectiveRatio !in t.perspectiveRatioRange -> Decision.Reject(Hint.HOLD_PARALLEL)
            m.curlPer100Px > t.maxCurlPer100Px -> Decision.Reject(Hint.FLATTEN_PAGE)
            m.contrast < t.minContrast -> Decision.Reject(Hint.MORE_LIGHT)
            else -> Decision.Accept(m.frameWeight(t.sharpnessScale))
        }
    }
}
