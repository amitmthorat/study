package com.airline.mrz.core.result

import com.airline.mrz.core.model.CheckDigitKind
import com.airline.mrz.core.model.CheckStatus
import com.airline.mrz.core.model.Field
import com.airline.mrz.core.model.PassportData
import com.airline.mrz.core.model.Position
import java.io.Serializable

/** A single character substitution made by the corrector, kept for audit. */
public data class Correction(
    val position: Position,
    val from: Char,
    val to: Char,
    /** Probability the recognizer assigned to [to] at that cell. */
    val probability: Float,
    /** The check digit that motivated the swap. */
    val motivatedBy: CheckDigitKind,
) : Serializable

/** Outcome of comparing visual-zone reads with the MRZ. */
public sealed interface VizCrossCheck : Serializable {
    public object Agrees : VizCrossCheck {
        private fun readResolve(): Any = Agrees
    }

    public data class Disagrees(val fields: Set<Field>) : VizCrossCheck

    public object NotRead : VizCrossCheck {
        private fun readResolve(): Any = NotRead
    }
}

/** Everything about how the MRZ was read, independent of what it says. */
public data class MrzRead(
    val rawLine1: String,
    val rawLine2: String,
    val correctedLine1: String,
    val correctedLine2: String,
    val corrections: List<Correction>,
    val checks: Map<CheckDigitKind, CheckStatus>,
    val vizCrossCheck: VizCrossCheck,
    val framesVoted: Int,
    val durationMs: Long,
    /** Minimum top-two margin across all 88 cells of the integrated read. */
    val minMargin: Float,
) : Serializable {
    val allChecksPass: Boolean get() = checks.values.all { it != CheckStatus.FAIL }
}

/** Why a session ended without a verified read. */
public enum class FailureReason {
    TIMEOUT,
    CANCELLED,
    VIZ_MISMATCH,
    ENGINE_ERROR,
}

/** The core's view of a finished session, before the SDK wraps it with diagnostics. */
public sealed interface MrzReadOutcome {
    public data class Verified(val passport: PassportData, val read: MrzRead) : MrzReadOutcome
    public data class Unverified(val passport: PassportData?, val read: MrzRead?, val reason: FailureReason) : MrzReadOutcome
    public data class Failed(val reason: FailureReason, val detail: String? = null) : MrzReadOutcome
}
