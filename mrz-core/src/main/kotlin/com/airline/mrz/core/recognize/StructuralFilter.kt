package com.airline.mrz.core.recognize

import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout

/**
 * Cheap per-frame sanity check run before a frame is allowed to vote.
 *
 * A frame whose argmax string is not even shaped like a TD3 MRZ (wrong document type
 * character, letters in a date, digits in a name) would poison the accumulator. Such
 * frames are dropped, not down-weighted.
 */
public object StructuralFilter {

    public data class Verdict(val accepted: Boolean, val reason: String? = null)

    /**
     * Fraction of fixed-alphabet cells whose restricted argmax differs from the raw
     * argmax above which the frame is rejected. A few confusable swaps are expected;
     * a frame where a third of the cells are out of alphabet is not an MRZ read.
     */
    public const val MAX_OUT_OF_ALPHABET_FRACTION: Float = 0.15f

    public fun check(frame: FrameHypothesis): Verdict {
        val l1 = frame.line1.rawText
        val l2 = frame.line2.rawText
        if (l1[0] != 'P') return Verdict(false, "document type is '${l1[0]}', expected 'P'")

        var fixedCells = 0
        var outOfAlphabet = 0
        for (line in 0 until Td3Layout.LINE_COUNT) {
            val text = if (line == 0) l1 else l2
            for (i in 0 until Td3Layout.LINE_LENGTH) {
                val alphabet = Td3Layout.alphabetAt(line, i)
                if (alphabet.size == MrzAlphabet.SIZE) continue
                fixedCells++
                if (text[i] !in alphabet) outOfAlphabet++
            }
        }
        val fraction = outOfAlphabet.toFloat() / fixedCells
        if (fraction > MAX_OUT_OF_ALPHABET_FRACTION) {
            return Verdict(false, "$outOfAlphabet of $fixedCells fixed-alphabet cells out of alphabet")
        }
        // The expiry date must be all digits in every real passport; a line 2 whose
        // expiry cells are mostly fillers is a misaligned read.
        val expiry = Td3Layout.DATE_OF_EXPIRY.extract(listOf(l1, l2))
        if (expiry.count { it == MrzAlphabet.FILLER } > 2) return Verdict(false, "expiry field is mostly fillers")
        return Verdict(true)
    }
}
