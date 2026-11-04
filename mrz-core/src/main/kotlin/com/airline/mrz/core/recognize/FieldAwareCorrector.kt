package com.airline.mrz.core.recognize

import com.airline.mrz.core.model.CheckDigitKind
import com.airline.mrz.core.model.CheckStatus
import com.airline.mrz.core.model.Field
import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Position
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.parse.CheckDigit
import com.airline.mrz.core.result.Correction

/**
 * Turns per-cell distributions into a raw string and a corrected string.
 *
 * Two mechanisms, applied in order:
 *
 * 1. **Alphabet restriction.** Every cell's distribution is restricted to the alphabet
 *    its field allows (digits in dates, letters in names, `P` in position 1) and the
 *    restricted argmax is taken. This resolves most `0/O`, `1/I`, `5/S`, `8/B` swaps.
 * 2. **Check-digit guided swap.** For a field whose check digit still fails, if exactly
 *    one cell in the field (including the check cell) has a top-two margin below
 *    [maxSwapMargin] and swapping it to its runner-up makes the check pass, that swap is
 *    applied and recorded. At most one swap per field. Applied only to the date fields:
 *    document number and optional data have loose structure and are left to voting and
 *    the visual-zone cross-check.
 *
 * The raw string is never modified; both strings and every swap are returned so the
 * result is auditable. This corrector is intended to run on the *integrated*
 * multi-frame distribution, not on single frames, so that a swap is only ever made
 * when the accumulated evidence is genuinely ambiguous.
 */
public class FieldAwareCorrector(
    private val maxSwapMargin: Float = 0.15f,
) {
    public data class Output(
        val rawLines: List<String>,
        val correctedLines: List<String>,
        val corrections: List<Correction>,
        val checks: Map<CheckDigitKind, CheckStatus>,
        /** Restricted distributions, cell by cell, for confidence reporting. */
        val restricted: List<List<CharDistribution>>,
    ) {
        public fun fieldConfidence(): Map<Field, Float> {
            val out = LinkedHashMap<Field, Float>()
            for ((field, span) in Td3Layout.FIELD_SPANS) {
                var min = 1f
                for (p in span.positions()) min = minOf(min, restricted[p.line][p.index].margin)
                out[field] = min
            }
            return out
        }
    }

    /** Fields eligible for a check-digit guided swap. */
    private val swappable: Set<CheckDigitKind> = setOf(CheckDigitKind.DATE_OF_BIRTH, CheckDigitKind.DATE_OF_EXPIRY)

    public fun correct(line1: List<CharDistribution>, line2: List<CharDistribution>): Output {
        require(line1.size == Td3Layout.LINE_LENGTH && line2.size == Td3Layout.LINE_LENGTH)
        val raw = listOf(line1, line2)
        val rawLines = raw.map { cells -> String(CharArray(Td3Layout.LINE_LENGTH) { cells[it].argmaxChar }) }

        val restricted = raw.mapIndexed { line, cells ->
            cells.mapIndexed { i, d -> d.restrictedTo(Td3Layout.alphabetAt(line, i)) }
        }
        val corrected = restricted.map { cells -> CharArray(Td3Layout.LINE_LENGTH) { cells[it].argmaxChar } }
        val corrections = ArrayList<Correction>()

        for (spec in Td3Layout.CHECKS) {
            if (spec.kind !in swappable) continue
            if (verify(spec, corrected)) continue
            val candidates = (spec.data + spec.check).flatMap { it.positions() }
                .filter { restricted[it.line][it.index].margin < maxSwapMargin }
            if (candidates.size != 1) continue
            val pos = candidates[0]
            val (_, second) = restricted[pos.line][pos.index].topTwo()
            if (second < 0) continue
            val from = corrected[pos.line][pos.index]
            val to = MrzAlphabet.charAt(second)
            if (to !in Td3Layout.alphabetAt(pos)) continue
            corrected[pos.line][pos.index] = to
            if (verify(spec, corrected)) {
                corrections += Correction(pos, from, to, restricted[pos.line][pos.index][second], spec.kind)
            } else {
                corrected[pos.line][pos.index] = from
            }
        }

        val correctedLines = corrected.map { String(it) }
        val checks = LinkedHashMap<CheckDigitKind, CheckStatus>()
        for (spec in Td3Layout.CHECKS) {
            checks[spec.kind] = if (verify(spec, corrected)) CheckStatus.PASS else CheckStatus.FAIL
        }
        return Output(rawLines, correctedLines, corrections, checks, restricted)
    }

    private fun verify(spec: Td3Layout.CheckSpec, lines: List<CharArray>): Boolean {
        val data = buildString { for (s in spec.data) for (p in s.positions()) append(lines[p.line][p.index]) }
        val check = lines[spec.check.line][spec.check.start]
        return when (spec.kind) {
            CheckDigitKind.OPTIONAL_DATA -> CheckDigit.verify(data, check, allowFillerForEmpty = true)
            else -> CheckDigit.verify(data, check)
        }
    }
}

/** Convenience: which [Field] a [Position] falls in, if any. */
public fun Position.field(): Field? = Td3Layout.FIELD_SPANS.entries.firstOrNull { (_, s) ->
    s.line == line && index in s.start until s.end
}?.key
