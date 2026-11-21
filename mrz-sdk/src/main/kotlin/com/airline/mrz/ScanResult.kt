package com.airline.mrz

import com.airline.mrz.core.model.PassportData
import com.airline.mrz.core.result.FailureReason
import com.airline.mrz.core.result.MrzRead
import com.airline.mrz.diagnostics.DiagnosticBundle
import java.io.Serializable

/**
 * Outcome of a scanning session.
 *
 * [Success] means the read was verified: every check digit passed, the result was
 * stable across frames with a confidence margin, and the visual-zone cross-check (when
 * available) agreed. It never means the document is valid; expiry and acceptance rules
 * belong to the airline's own logic.
 */
public sealed interface ScanResult : Serializable {
    public data class Success(
        val passport: PassportData,
        val read: MrzRead,
        val diagnostics: DiagnosticBundle,
    ) : ScanResult

    /** Best effort read returned on timeout (when allowed) or on a persistent visual-zone mismatch. */
    public data class Unverified(
        val passport: PassportData?,
        val read: MrzRead?,
        val reason: FailureReason,
        val diagnostics: DiagnosticBundle,
    ) : ScanResult

    public data class Failed(
        val reason: FailureReason,
        val detail: String?,
        val diagnostics: DiagnosticBundle,
    ) : ScanResult

    public object Cancelled : ScanResult {
        private fun readResolve(): Any = Cancelled
    }
}
