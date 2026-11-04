package com.airline.mrz.core.recognize

import com.airline.mrz.core.model.Td3Layout

/**
 * One frame's recognition of one MRZ line: 44 per-cell distributions plus the weights
 * the integrator should apply.
 *
 * [frameWeight] is a frame-level quality score (focus, glare, detector confidence) in
 * `0..1`. [cellWeights] are per-cell quality scores (local sharpness, local glare) in
 * `0..1`; a cell can be smeared in an otherwise sharp frame.
 */
public class LineHypothesis(
    public val cells: List<CharDistribution>,
    public val frameWeight: Float = 1f,
    public val cellWeights: FloatArray = FloatArray(Td3Layout.LINE_LENGTH) { 1f },
) {
    init {
        require(cells.size == Td3Layout.LINE_LENGTH) { "a TD3 line has ${Td3Layout.LINE_LENGTH} cells, got ${cells.size}" }
        require(cellWeights.size == Td3Layout.LINE_LENGTH)
        require(frameWeight in 0f..1f) { "frameWeight must be in 0..1" }
    }

    /** The argmax string over the full alphabet, without any field restriction. */
    public val rawText: String
        get() = buildString(Td3Layout.LINE_LENGTH) { for (c in cells) append(c.argmaxChar) }

    public companion object {
        /** Convenience for tests and the ML Kit stand-in: a hypothesis from a plain string. */
        public fun fromText(text: String, confidence: Float = 0.9f, frameWeight: Float = 1f): LineHypothesis {
            require(text.length == Td3Layout.LINE_LENGTH)
            return LineHypothesis(text.map { CharDistribution.oneHot(it, confidence) }, frameWeight)
        }
    }
}

/** Both lines of one frame. */
public class FrameHypothesis(
    public val line1: LineHypothesis,
    public val line2: LineHypothesis,
) {
    public val lines: List<LineHypothesis> get() = listOf(line1, line2)
    public val frameWeight: Float get() = minOf(line1.frameWeight, line2.frameWeight)

    public companion object {
        public fun fromText(line1: String, line2: String, confidence: Float = 0.9f, frameWeight: Float = 1f): FrameHypothesis =
            FrameHypothesis(LineHypothesis.fromText(line1, confidence, frameWeight), LineHypothesis.fromText(line2, confidence, frameWeight))
    }
}
