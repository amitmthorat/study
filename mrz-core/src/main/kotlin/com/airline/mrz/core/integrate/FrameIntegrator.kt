package com.airline.mrz.core.integrate

import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.recognize.CharDistribution
import com.airline.mrz.core.recognize.FrameHypothesis
import com.airline.mrz.core.recognize.StructuralFilter

/**
 * Accumulates per-frame hypotheses into one integrated read.
 *
 * Positions are fixed (44 per line), so no sequence alignment is needed. For every cell
 * the integrator keeps a weighted sum of the per-frame distributions, where the weight
 * is `frameWeight * cellWeight`. Only the best [keepFraction] of the most recent
 * [windowSize] voted frames (by frame weight) contribute, following the finding that
 * combining the best half of frames with per-character weighting performs best.
 *
 * Frames failing [StructuralFilter] are rejected and never vote.
 *
 * The integrator also watches for a competing document number (the agent moved to the
 * next passenger without closing the screen) and resets itself when one persists.
 */
public class FrameIntegrator(
    private val windowSize: Int = 12,
    private val keepFraction: Float = 0.5f,
    private val minFramesToKeep: Int = 3,
    private val documentChangeMargin: Float = 0.5f,
    private val documentChangeFrames: Int = 3,
) {
    init {
        require(windowSize >= 1 && keepFraction in 0f..1f && minFramesToKeep >= 1)
    }

    public sealed interface AddResult {
        public object Voted : AddResult
        public data class Rejected(val reason: String) : AddResult
        /** A different document appeared; the accumulator was cleared and this frame started it. */
        public object ResetAndVoted : AddResult
    }

    /** The integrated distributions plus bookkeeping. */
    public class Integrated(
        public val line1: List<CharDistribution>,
        public val line2: List<CharDistribution>,
        public val framesVoted: Int,
        public val framesContributing: Int,
        public val totalWeight: Float,
    ) {
        public val minMargin: Float
            get() = minOf(line1.minOf { it.margin }, line2.minOf { it.margin })

        public val rawLines: List<String>
            get() = listOf(line1, line2).map { cells -> String(CharArray(cells.size) { cells[it].argmaxChar }) }
    }

    private val window = ArrayDeque<FrameHypothesis>()
    private var framesVoted = 0
    private var candidateDocNumber: String? = null
    private var candidateDocNumberFrames = 0

    public fun add(frame: FrameHypothesis): AddResult {
        val verdict = StructuralFilter.check(frame)
        if (!verdict.accepted) return AddResult.Rejected(verdict.reason ?: "structural")

        var reset = false
        if (detectDocumentChange(frame)) {
            reset()
            reset = true
        }

        window.addLast(frame)
        while (window.size > windowSize) window.removeFirst()
        framesVoted++
        return if (reset) AddResult.ResetAndVoted else AddResult.Voted
    }

    public fun reset() {
        window.clear()
        framesVoted = 0
        candidateDocNumber = null
        candidateDocNumberFrames = 0
    }

    public val isEmpty: Boolean get() = window.isEmpty()

    /** Returns null until at least one frame has voted. */
    public fun integrated(): Integrated? {
        if (window.isEmpty()) return null
        val selected = selectFrames()
        var total = 0f
        for (f in selected) total += f.frameWeight

        fun combineLine(lineIndex: Int): List<CharDistribution> = List(Td3Layout.LINE_LENGTH) { cell ->
            CharDistribution.combine(selected.map { f ->
                val line = f.lines[lineIndex]
                line.cells[cell] to (line.frameWeight * line.cellWeights[cell])
            })
        }
        return Integrated(combineLine(0), combineLine(1), framesVoted, selected.size, total)
    }

    /**
     * Upper bound on how much one more frame of average weight could move any cell's
     * top probability. Used by the stopping rule's early-stop: when this is tiny, more
     * frames cannot change the answer.
     */
    public fun expectedChangeBound(): Float {
        val selected = selectFrames()
        if (selected.isEmpty()) return 1f
        val total = selected.sumOf { it.frameWeight.toDouble() }.toFloat()
        val mean = total / selected.size
        // Adding a frame of weight `mean` scales existing mass by total/(total+mean);
        // the top probability can change by at most mean/(total+mean).
        return mean / (total + mean)
    }

    private fun selectFrames(): List<FrameHypothesis> {
        if (window.isEmpty()) return emptyList()
        val keep = maxOf(minFramesToKeep, (window.size * keepFraction).toInt()).coerceAtMost(window.size)
        return window.sortedByDescending { it.frameWeight }.take(keep)
    }

    private fun detectDocumentChange(frame: FrameHypothesis): Boolean {
        val current = integrated() ?: return false
        val span = Td3Layout.DOCUMENT_NUMBER
        val currentDoc = span.extract(current.rawLines)
        val cells = frame.line2.cells.subList(span.start, span.end)
        val frameDoc = String(CharArray(cells.size) { cells[it].argmaxChar })
        val confident = cells.all { it.margin >= documentChangeMargin }
        if (frameDoc == currentDoc || !confident) {
            candidateDocNumber = null
            candidateDocNumberFrames = 0
            return false
        }
        if (frameDoc == candidateDocNumber) {
            candidateDocNumberFrames++
        } else {
            candidateDocNumber = frameDoc
            candidateDocNumberFrames = 1
        }
        return candidateDocNumberFrames >= documentChangeFrames
    }
}
