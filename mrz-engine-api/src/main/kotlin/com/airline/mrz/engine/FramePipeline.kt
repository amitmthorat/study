package com.airline.mrz.engine

import com.airline.mrz.core.integrate.FrameIntegrator
import com.airline.mrz.core.integrate.ReadSession
import com.airline.mrz.core.integrate.StoppingRule
import com.airline.mrz.core.model.Field
import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.recognize.FrameHypothesis
import com.airline.mrz.core.result.MrzReadOutcome
import com.airline.mrz.core.result.VizCrossCheck
import com.airline.mrz.engine.image.Downscaler
import com.airline.mrz.engine.image.LumaImage
import com.airline.mrz.engine.image.Quality
import com.airline.mrz.engine.image.Rotate

/**
 * The per-frame orchestration: detect, gate, rectify, recognise, vote, cross-check,
 * decide. Pure Kotlin so it can be driven by recorded frames on the JVM.
 *
 * Not thread-safe: the camera layer calls [process] from its single analysis thread.
 */
public class FramePipeline(
    private val engine: MrzEngine,
    private val gate: QualityGate = QualityGate(),
    private val readSession: ReadSession = ReadSession(),
    private val config: Config = Config(),
    private val clock: () -> Long = System::nanoTime,
) {
    public data class Config(
        val vizCrossCheck: Boolean = true,
        /** Voted frames between visual-zone read attempts. */
        val vizEveryFrames: Int = 4,
        /** Band centre jump, as a fraction of band width, that resets the accumulator. */
        val geometryResetFraction: Float = 0.3f,
        /** Recognizer-to-frame-interval ratio above which only sharper frames are recognised. */
        val strictModeLatencyRatio: Float = 1.5f,
    )

    /** What happened to one frame. The UI maps these to [ScanState]-like states. */
    public sealed interface Event {
        public object NoMrz : Event
        public data class Rejected(val hint: Hint, val band: Quad) : Event
        public data class Voted(val band: Quad, val progress: StoppingRule.Progress) : Event
        public data class Dropped(val band: Quad, val reason: String) : Event
        public data class Done(val outcome: MrzReadOutcome) : Event
    }

    public class Timings {
        public var detectNanos: Long = 0
        public var rectifyNanos: Long = 0
        public var qualityNanos: Long = 0
        public var recognizeNanos: Long = 0
        public var vizNanos: Long = 0
        public var framesSeen: Int = 0
        public var framesVoted: Int = 0
        public var framesRejected: Int = 0
        public val hintCounts: MutableMap<Hint, Int> = LinkedHashMap()
    }

    public val timings: Timings = Timings()

    private var startNanos: Long = -1
    private var lastBand: Quad? = null
    private var lastFrameNanos: Long = -1
    private var frameIntervalNanos: Long = 0
    private var viz: VizCrossCheck = VizCrossCheck.NotRead
    private var votedSinceViz = 0
    private var finished = false
    private var strict = false

    public fun reset() {
        readSession.reset()
        startNanos = -1
        lastBand = null
        lastFrameNanos = -1
        viz = VizCrossCheck.NotRead
        votedSinceViz = 0
        finished = false
    }

    /** Elapsed session time in milliseconds, 0 before the first frame. */
    public fun elapsedMs(): Long = if (startNanos < 0) 0 else (clock() - startNanos) / 1_000_000

    /** Call when no frame arrived for a while so timeouts still fire. */
    public fun tick(): Event? {
        if (finished || startNanos < 0) return null
        return when (val d = readSession.onTick(viz, elapsedMs())) {
            is StoppingRule.Decision.Stop -> {
                finished = true; Event.Done(d.outcome)
            }
            is StoppingRule.Decision.Continue -> null
        }
    }

    public fun process(frame: Frame): Event {
        val now = clock()
        if (startNanos < 0) startNanos = now
        if (lastFrameNanos >= 0) frameIntervalNanos = now - lastFrameNanos
        lastFrameNanos = now
        timings.framesSeen++
        if (finished) return Event.Done(lastOutcome ?: error("finished without outcome"))

        val upright = if (frame.rotationDegrees == 0) frame.luma else Rotate.clockwise(frame.luma, frame.rotationDegrees)

        // 1. Detect on a downscaled copy.
        val t0 = clock()
        val factor = Downscaler.factorFor(upright.width, upright.height, engine.detector.inputLongSide)
        val small = Downscaler.downscale(upright, factor)
        val detection = engine.detector.detect(small)
        timings.detectNanos += clock() - t0
        if (detection == null) {
            lastBand = null
            return tickOr(Event.NoMrz)
        }
        val band = detection.band.scaled(factor.toFloat(), factor.toFloat())

        // Reset the accumulator when the band jumps: a different document is in view.
        lastBand?.let { prev ->
            val jump = centre(prev).distanceTo(centre(band))
            if (jump > prev.width * config.geometryResetFraction) readSession.reset()
        }
        lastBand = band

        // 2. Rectify, 3. measure, 4. gate.
        val t1 = clock()
        val rectified = engine.rectifier.rectify(upright, band)
        timings.rectifyNanos += clock() - t1
        val t2 = clock()
        val metrics = Quality.measure(upright.width, upright.height, band, detection.confidence, rectified)
        val decision = gate.evaluate(metrics, strict)
        timings.qualityNanos += clock() - t2
        val frameWeight = when (decision) {
            is QualityGate.Decision.Reject -> {
                timings.framesRejected++
                timings.hintCounts.merge(decision.hint, 1, Int::plus)
                return tickOr(Event.Rejected(decision.hint, band))
            }
            is QualityGate.Decision.Accept -> decision.frameWeight
        }

        // 5. Recognise with per-cell weights.
        val t3 = clock()
        val cw = engine.rectifier.cellWidth
        val cellWeights = listOf(
            Quality.cellWeights(rectified.line1, cw, Td3Layout.LINE_LENGTH),
            Quality.cellWeights(rectified.line2, cw, Td3Layout.LINE_LENGTH),
        )
        val lines = engine.recognizer.recognize(listOf(rectified.line1, rectified.line2), frameWeight, cellWeights)
        val recognizeNanos = clock() - t3
        timings.recognizeNanos += recognizeNanos
        updateStrictMode(recognizeNanos)
        if (lines.size != 2) return Event.Dropped(band, "recognizer returned ${lines.size} lines")
        val hypothesis = FrameHypothesis(lines[0], lines[1])

        // 6. Vote and 9. decide.
        val (add, ruling) = readSession.onFrame(hypothesis, viz, elapsedMs())
        if (add is FrameIntegrator.AddResult.Rejected) {
            return tickOr(Event.Dropped(band, add.reason))
        }
        timings.framesVoted++
        votedSinceViz++

        // 8. Visual-zone cross-check once the MRZ checks pass, every few frames.
        if (config.vizCrossCheck && engine.vizReader != null && ruling is StoppingRule.Decision.Continue &&
            ruling.progress.checksPassing && votedSinceViz >= config.vizEveryFrames
        ) {
            votedSinceViz = 0
            val t4 = clock()
            viz = crossCheck(upright, band)
            timings.vizNanos += clock() - t4
        }

        return when (ruling) {
            is StoppingRule.Decision.Stop -> {
                finished = true; lastOutcome = ruling.outcome; Event.Done(ruling.outcome)
            }
            is StoppingRule.Decision.Continue -> Event.Voted(band, ruling.progress)
        }
    }

    private var lastOutcome: MrzReadOutcome? = null

    private fun tickOr(event: Event): Event = tick() ?: event

    private fun updateStrictMode(recognizeNanos: Long) {
        if (frameIntervalNanos <= 0) return
        strict = recognizeNanos.toFloat() / frameIntervalNanos > config.strictModeLatencyRatio
    }

    private fun centre(q: Quad): PointF = PointF(
        (q.topLeft.x + q.topRight.x + q.bottomRight.x + q.bottomLeft.x) / 4f,
        (q.topLeft.y + q.topRight.y + q.bottomRight.y + q.bottomLeft.y) / 4f,
    )

    private fun crossCheck(upright: LumaImage, band: Quad): VizCrossCheck {
        val integrated = readSession.integrated() ?: return VizCrossCheck.NotRead
        val lines = integrated.rawLines
        val issuing = Td3Layout.ISSUING_STATE.extract(lines)
        val fields = engine.vizReader?.read(upright, band, issuing) ?: return VizCrossCheck.NotRead
        val mismatches = HashSet<Field>()
        fields.documentNumber?.let {
            val mrz = Td3Layout.DOCUMENT_NUMBER.extract(lines).trimEnd(MrzAlphabet.FILLER)
            if (normaliseDocNo(it) != normaliseDocNo(mrz)) mismatches += Field.DOCUMENT_NUMBER
        }
        fields.dateOfBirth?.let { if (it != Td3Layout.DATE_OF_BIRTH.extract(lines)) mismatches += Field.DATE_OF_BIRTH }
        fields.dateOfExpiry?.let { if (it != Td3Layout.DATE_OF_EXPIRY.extract(lines)) mismatches += Field.DATE_OF_EXPIRY }
        if (fields.readFields.isEmpty()) return VizCrossCheck.NotRead
        return if (mismatches.isEmpty()) VizCrossCheck.Agrees else VizCrossCheck.Disagrees(mismatches)
    }

    private fun normaliseDocNo(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()
}
