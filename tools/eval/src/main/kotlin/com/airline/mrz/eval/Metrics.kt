package com.airline.mrz.eval

import com.airline.mrz.core.model.Field
import com.airline.mrz.core.model.Td3Layout

/**
 * One evaluated session: what the SDK returned versus the labelled truth.
 *
 * [predictedLine1]/[predictedLine2] are null when the session did not produce a
 * verified result. [verified] is true when the SDK returned `Success`.
 */
public data class EvalRecord(
    val id: String,
    val issuingState: String,
    val deviceTier: String,
    val truthLine1: String,
    val truthLine2: String,
    val predictedLine1: String?,
    val predictedLine2: String?,
    val verified: Boolean,
    val durationMs: Long?,
    val corrections: Int = 0,
)

/** The release-gate metrics from the accuracy strategy, computed over a set of records. */
public data class MetricsReport(
    val sessions: Int,
    val verifiedRate: Double,
    /** Verified sessions whose 88 characters all match the truth, over all sessions. */
    val exactMrzAccuracy: Double,
    /** Verified sessions with any field wrong, over verified sessions. The number that matters. */
    val falseAcceptRate: Double,
    val falseAccepts: List<String>,
    val perFieldAccuracy: Map<Field, Double>,
    val perIssuerExactAccuracy: Map<String, Double>,
    val perTierVerifiedRate: Map<String, Double>,
    val medianDurationMs: Long?,
    val p95DurationMs: Long?,
    val correctionRate: Double,
) {
    public fun render(): String = buildString {
        appendLine("sessions            $sessions")
        appendLine("verified rate       ${pct(verifiedRate)}")
        appendLine("exact-MRZ accuracy  ${pct(exactMrzAccuracy)}")
        appendLine("false accept rate   ${pct(falseAcceptRate)}  (${falseAccepts.size} sessions)")
        appendLine("correction rate     ${pct(correctionRate)}")
        appendLine("duration median/p95 ${medianDurationMs ?: "-"} / ${p95DurationMs ?: "-"} ms")
        appendLine("per field:")
        for ((f, v) in perFieldAccuracy) appendLine("  ${f.name.padEnd(16)} ${pct(v)}")
        appendLine("per issuer (exact):")
        for ((k, v) in perIssuerExactAccuracy.toSortedMap()) appendLine("  ${k.padEnd(16)} ${pct(v)}")
        appendLine("per tier (verified):")
        for ((k, v) in perTierVerifiedRate.toSortedMap()) appendLine("  ${k.padEnd(16)} ${pct(v)}")
        if (falseAccepts.isNotEmpty()) appendLine("FALSE ACCEPTS: ${falseAccepts.joinToString()}")
    }

    private fun pct(v: Double) = "%.2f%%".format(v * 100)
}

public object Metrics {

    public fun compute(records: List<EvalRecord>): MetricsReport {
        require(records.isNotEmpty())
        val verified = records.filter { it.verified && it.predictedLine1 != null && it.predictedLine2 != null }
        val exact = verified.filter { it.predictedLine1 == it.truthLine1 && it.predictedLine2 == it.truthLine2 }
        val falseAccepts = verified.filter { r -> Field.entries.any { !fieldMatches(r, it) } }

        val perField = Field.entries.associateWith { f ->
            if (verified.isEmpty()) 0.0 else verified.count { fieldMatches(it, f) }.toDouble() / verified.size
        }
        val perIssuer = records.groupBy { it.issuingState }.mapValues { (_, rs) ->
            rs.count { it.verified && it.predictedLine1 == it.truthLine1 && it.predictedLine2 == it.truthLine2 }.toDouble() / rs.size
        }
        val perTier = records.groupBy { it.deviceTier }.mapValues { (_, rs) -> rs.count { it.verified }.toDouble() / rs.size }
        val durations = verified.mapNotNull { it.durationMs }.sorted()

        return MetricsReport(
            sessions = records.size,
            verifiedRate = verified.size.toDouble() / records.size,
            exactMrzAccuracy = exact.size.toDouble() / records.size,
            falseAcceptRate = if (verified.isEmpty()) 0.0 else falseAccepts.size.toDouble() / verified.size,
            falseAccepts = falseAccepts.map { it.id },
            perFieldAccuracy = perField,
            perIssuerExactAccuracy = perIssuer,
            perTierVerifiedRate = perTier,
            medianDurationMs = durations.getOrNull(durations.size / 2),
            p95DurationMs = durations.getOrNull((durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)),
            correctionRate = if (verified.isEmpty()) 0.0 else verified.count { it.corrections > 0 }.toDouble() / verified.size,
        )
    }

    /** Compares the characters of one field between prediction and truth. */
    public fun fieldMatches(r: EvalRecord, field: Field): Boolean {
        val span = Td3Layout.FIELD_SPANS.getValue(field)
        val truth = span.extract(listOf(r.truthLine1, r.truthLine2))
        val pred = span.extract(listOf(r.predictedLine1!!, r.predictedLine2!!))
        return truth == pred
    }
}
