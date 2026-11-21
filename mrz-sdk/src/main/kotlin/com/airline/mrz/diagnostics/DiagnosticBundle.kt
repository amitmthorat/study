package com.airline.mrz.diagnostics

import java.io.Serializable

/**
 * Support data attached to every result. The schema has no free-text fields and never
 * carries MRZ content, names or images: it can be logged and sent to support as is.
 */
public data class DiagnosticBundle(
    val sdkVersion: String,
    val modelVersion: String,
    val deviceModel: String,
    val androidSdk: Int,
    val accelerator: String,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val framesSeen: Int,
    val framesVoted: Int,
    val framesRejected: Int,
    /** Count of frames rejected per hint name. */
    val hintCounts: Map<String, Int>,
    val durationMs: Long,
    /** Mean per-stage times in milliseconds; empty unless timings diagnostics are enabled. */
    val stageMeansMs: Map<String, Float>,
) : Serializable {
    public companion object {
        private const val serialVersionUID: Long = 1L
    }
}
