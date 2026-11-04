package com.airline.mrz.core

import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.parse.DateParser
import com.airline.mrz.core.recognize.CharDistribution
import com.airline.mrz.core.recognize.FrameHypothesis
import com.airline.mrz.core.recognize.LineHypothesis
import java.time.LocalDate
import kotlin.random.Random

/** Shared test data: the ICAO Doc 9303 specimen and helpers to build noisy hypotheses. */
object Fixtures {
    const val SPECIMEN_L1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    const val SPECIMEN_L2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    val TODAY: LocalDate = LocalDate.of(2026, 11, 4)
    val dates = DateParser { TODAY }

    /** Pads or validates a line to exactly 44 characters. */
    fun line(s: String): String {
        require(s.length <= Td3Layout.LINE_LENGTH)
        return s.padEnd(Td3Layout.LINE_LENGTH, MrzAlphabet.FILLER)
    }

    /**
     * A hypothesis whose cells put [confidence] on the true character, except at
     * [swaps] where the given wrong character gets the top probability and the true
     * character is the runner-up with probability `top - [swapMargin]`.
     */
    fun noisyLine(
        truth: String,
        confidence: Float = 0.95f,
        swaps: Map<Int, Char> = emptyMap(),
        swapMargin: Float = 0.1f,
        frameWeight: Float = 1f,
        cellWeights: FloatArray = FloatArray(Td3Layout.LINE_LENGTH) { 1f },
    ): LineHypothesis {
        require(truth.length == Td3Layout.LINE_LENGTH)
        val cells = truth.mapIndexed { i, c ->
            val wrong = swaps[i]
            if (wrong == null) {
                CharDistribution.oneHot(c, confidence)
            } else {
                val arr = FloatArray(MrzAlphabet.SIZE) { 0.001f }
                val top = 0.5f + swapMargin / 2
                arr[MrzAlphabet.indexOf(wrong)] = top
                arr[MrzAlphabet.indexOf(c)] = top - swapMargin
                CharDistribution.of(arr)
            }
        }
        return LineHypothesis(cells, frameWeight, cellWeights)
    }

    fun frame(
        l1: String = SPECIMEN_L1,
        l2: String = SPECIMEN_L2,
        confidence: Float = 0.95f,
        swaps1: Map<Int, Char> = emptyMap(),
        swaps2: Map<Int, Char> = emptyMap(),
        frameWeight: Float = 1f,
    ): FrameHypothesis = FrameHypothesis(
        noisyLine(l1, confidence, swaps1, frameWeight = frameWeight),
        noisyLine(l2, confidence, swaps2, frameWeight = frameWeight),
    )

    /** A frame with [errors] random single-cell substitutions on line 2, for convergence tests. */
    fun randomlyCorruptedFrame(rng: Random, errors: Int, confidence: Float = 0.8f, frameWeight: Float = 1f): FrameHypothesis {
        val swaps = HashMap<Int, Char>()
        while (swaps.size < errors) {
            val i = rng.nextInt(Td3Layout.LINE_LENGTH)
            val truth = SPECIMEN_L2[i]
            var wrong: Char
            do wrong = MrzAlphabet.charAt(rng.nextInt(MrzAlphabet.SIZE)) while (wrong == truth)
            swaps[i] = wrong
        }
        return frame(confidence = confidence, swaps2 = swaps, frameWeight = frameWeight)
    }
}
