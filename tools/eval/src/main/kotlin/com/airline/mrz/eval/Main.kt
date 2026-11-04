package com.airline.mrz.eval

import java.io.File
import kotlin.system.exitProcess

/**
 * Evaluates a results file against the accuracy gates.
 *
 * Input: a tab-separated file with a header row and the columns
 * `id, issuing_state, device_tier, truth_line1, truth_line2, predicted_line1,
 * predicted_line2, verified, duration_ms, corrections`. Empty prediction cells mean no
 * verified result. The file is produced by the on-device benchmark harness or by the
 * replay runner over recorded frame sequences.
 *
 * Exit code is non-zero when any false accept is present, so CI can gate on it.
 */
public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: eval <results.tsv> [--max-false-accepts N]")
        exitProcess(2)
    }
    val file = File(args[0])
    val maxFalseAccepts = args.indexOf("--max-false-accepts").let { if (it >= 0) args[it + 1].toInt() else 0 }
    val records = ResultsFile.read(file)
    val report = Metrics.compute(records)
    println(report.render())
    if (report.falseAccepts.size > maxFalseAccepts) {
        System.err.println("GATE FAILED: ${report.falseAccepts.size} false accepts (max $maxFalseAccepts)")
        exitProcess(1)
    }
}

public object ResultsFile {
    public fun read(file: File): List<EvalRecord> = file.useLines { lines ->
        val it = lines.iterator()
        require(it.hasNext()) { "empty results file" }
        val header = it.next().split('\t')
        fun col(name: String) = header.indexOf(name).also { i -> require(i >= 0) { "missing column $name" } }
        val cId = col("id"); val cIss = col("issuing_state"); val cTier = col("device_tier")
        val cT1 = col("truth_line1"); val cT2 = col("truth_line2"); val cP1 = col("predicted_line1"); val cP2 = col("predicted_line2")
        val cV = col("verified"); val cD = col("duration_ms"); val cC = col("corrections")
        buildList {
            for (line in it) {
                if (line.isBlank()) continue
                val f = line.split('\t')
                add(
                    EvalRecord(
                        id = f[cId], issuingState = f[cIss], deviceTier = f[cTier],
                        truthLine1 = f[cT1], truthLine2 = f[cT2],
                        predictedLine1 = f[cP1].ifBlank { null }, predictedLine2 = f[cP2].ifBlank { null },
                        verified = f[cV].toBoolean(), durationMs = f[cD].toLongOrNull(), corrections = f[cC].toIntOrNull() ?: 0,
                    ),
                )
            }
        }
    }
}
