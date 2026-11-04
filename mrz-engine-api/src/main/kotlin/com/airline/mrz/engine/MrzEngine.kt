package com.airline.mrz.engine

import com.airline.mrz.core.model.Field
import com.airline.mrz.core.recognize.LineHypothesis
import com.airline.mrz.engine.image.LumaImage
import com.airline.mrz.engine.image.Rectifier
import java.io.Closeable

/** Locates the MRZ band in a (downscaled) frame. */
public interface MrzDetector : Closeable {
    /** Longest side the detector wants its input downscaled to. */
    public val inputLongSide: Int

    /** Returns the band in the coordinates of [image], or null when no MRZ is visible. */
    public fun detect(image: LumaImage): Detection?
}

/** Recognises the characters of rectified MRZ lines. */
public interface MrzRecognizer : Closeable {
    /** Canonical line geometry the recognizer was trained for. */
    public val cellWidth: Int
    public val lineHeight: Int

    /**
     * Returns one hypothesis per input line, each with 44 calibrated distributions.
     * [frameWeight] and [cellWeights] are passed through onto the hypotheses.
     */
    public fun recognize(lines: List<LumaImage>, frameWeight: Float, cellWeights: List<FloatArray>): List<LineHypothesis>
}

/** Values read from the printed visual inspection zone, for cross-checking the MRZ. */
public data class VizFields(
    val documentNumber: String?,
    /** `YYMMDD` as the MRZ encodes it, after normalising the printed format. */
    val dateOfBirth: String?,
    val dateOfExpiry: String?,
) {
    public val readFields: Set<Field>
        get() = buildSet {
            if (documentNumber != null) add(Field.DOCUMENT_NUMBER)
            if (dateOfBirth != null) add(Field.DATE_OF_BIRTH)
            if (dateOfExpiry != null) add(Field.DATE_OF_EXPIRY)
        }
}

/** Reads the printed fields near the MRZ band. Optional; implementations may return null. */
public interface VizReader : Closeable {
    public fun read(frame: LumaImage, band: Quad, issuingState: String?): VizFields?
}

/**
 * Bundles the models an engine implementation provides. The pipeline in `mrz-sdk`
 * orchestrates them; this interface only exists so implementations are swappable.
 */
public interface MrzEngine : Closeable {
    public val detector: MrzDetector
    public val recognizer: MrzRecognizer
    public val vizReader: VizReader?
    public val rectifier: Rectifier

    /** Human-readable identifier of the models, for diagnostics. */
    public val modelVersion: String

    override fun close() {
        detector.close()
        recognizer.close()
        vizReader?.close()
    }
}
