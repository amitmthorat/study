package com.airline.mrz.engine

import com.airline.mrz.core.integrate.ReadSession
import com.airline.mrz.core.integrate.StoppingRule
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.parse.DateParser
import com.airline.mrz.core.parse.Td3Parser
import com.airline.mrz.core.recognize.CharDistribution
import com.airline.mrz.core.recognize.LineHypothesis
import com.airline.mrz.core.result.MrzReadOutcome
import com.airline.mrz.engine.image.LumaImage
import com.airline.mrz.engine.image.Rectifier
import com.airline.mrz.engine.image.SyntheticImages
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Drives the pipeline with a fake engine: a fixed detector and a recognizer that returns scripted text. */
class FramePipelineTest {
    private val l1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    private class FakeDetector(var result: Detection?) : MrzDetector {
        override val inputLongSide = 480
        override fun detect(image: LumaImage) = result
        override fun close() {}
    }

    private class ScriptedRecognizer(private val script: Iterator<Pair<String, String>>) : MrzRecognizer {
        override val cellWidth = 20
        override val lineHeight = 32
        var calls = 0
        override fun recognize(lines: List<LumaImage>, frameWeight: Float, cellWeights: List<FloatArray>): List<LineHypothesis> {
            calls++
            val (a, b) = script.next()
            return listOf(
                LineHypothesis(a.map { CharDistribution.oneHot(it, 0.95f) }, frameWeight, cellWeights[0]),
                LineHypothesis(b.map { CharDistribution.oneHot(it, 0.95f) }, frameWeight, cellWeights[1]),
            )
        }
        override fun close() {}
    }

    private class FakeViz(val fields: VizFields?) : VizReader {
        override fun read(frame: LumaImage, band: Quad, issuingState: String?) = fields
        override fun close() {}
    }

    private fun engine(detector: MrzDetector, recognizer: MrzRecognizer, viz: VizReader? = null) = object : MrzEngine {
        override val detector = detector
        override val recognizer = recognizer
        override val vizReader = viz
        override val rectifier = Rectifier()
        override val modelVersion = "fake"
    }

    /** A 1280x720 frame with a text-like band where the detector claims it is. */
    private fun frame(ts: Long): Frame {
        val img = SyntheticImages.flat(1280, 720, 220)
        val band = SyntheticImages.textLikeBand(880, 96)
        for (y in 0 until 96) for (x in 0 until 880) img.data[(500 + y) * 1280 + 200 + x] = band.data[y * 880 + x]
        return Frame(img, 0, ts)
    }

    private val bandQuad = Quad.ofRect(200f / 2, 500f / 2, 1080f / 2, 596f / 2) // detector works at 1/2 scale (1280 -> 640 > 480 => factor 2)

    private fun session() = ReadSession(rule = StoppingRule(StoppingRule.Config(), Td3Parser(DateParser { LocalDate.of(2026, 11, 4) })))

    @Test
    fun `clean frames reach a verified result`() {
        var now = 0L
        val script = generateSequence { l1 to l2 }.iterator()
        val recognizer = ScriptedRecognizer(script)
        val pipeline = FramePipeline(engine(FakeDetector(Detection(bandQuad, 0.9f)), recognizer), readSession = session(), clock = { now })
        var done: FramePipeline.Event.Done? = null
        for (i in 1..10) {
            now = i * 100_000_000L
            val e = pipeline.process(frame(now))
            if (e is FramePipeline.Event.Done) {
                done = e; break
            }
            assertIs<FramePipeline.Event.Voted>(e)
        }
        val v = assertIs<MrzReadOutcome.Verified>(done!!.outcome)
        assertEquals("L898902C3", v.passport.documentNumber)
        assertEquals(5, recognizer.calls)
        assertEquals(5, pipeline.timings.framesVoted)
    }

    @Test
    fun `no detection yields NoMrz and never calls the recognizer`() {
        val recognizer = ScriptedRecognizer(generateSequence { l1 to l2 }.iterator())
        val pipeline = FramePipeline(engine(FakeDetector(null), recognizer), readSession = session())
        assertIs<FramePipeline.Event.NoMrz>(pipeline.process(frame(1)))
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun `blurred frames are rejected with a hint`() {
        val recognizer = ScriptedRecognizer(generateSequence { l1 to l2 }.iterator())
        val pipeline = FramePipeline(engine(FakeDetector(Detection(bandQuad, 0.9f)), recognizer), readSession = session())
        val blurred = Frame(SyntheticImages.blurred(frame(1).luma, passes = 6), 0, 1)
        val e = pipeline.process(blurred)
        val r = assertIs<FramePipeline.Event.Rejected>(e)
        assertEquals(Hint.HOLD_STILL, r.hint)
        assertEquals(0, recognizer.calls)
        assertEquals(1, pipeline.timings.hintCounts[Hint.HOLD_STILL])
    }

    @Test
    fun `visual zone disagreement blocks success`() {
        var now = 0L
        val recognizer = ScriptedRecognizer(generateSequence { l1 to l2 }.iterator())
        val viz = FakeViz(VizFields(documentNumber = "L898902C9", dateOfBirth = "740812", dateOfExpiry = "120415"))
        val pipeline = FramePipeline(
            engine(FakeDetector(Detection(bandQuad, 0.9f)), recognizer, viz),
            readSession = session(),
            config = FramePipeline.Config(vizEveryFrames = 1),
            clock = { now },
        )
        var last: FramePipeline.Event? = null
        for (i in 1..15) {
            now = i * 100_000_000L
            last = pipeline.process(frame(now))
            if (last is FramePipeline.Event.Done) break
        }
        val done = assertIs<FramePipeline.Event.Done>(last)
        val u = assertIs<MrzReadOutcome.Unverified>(done.outcome)
        assertEquals(com.airline.mrz.core.result.FailureReason.VIZ_MISMATCH, u.reason)
    }

    @Test
    fun `timeout fires from tick without frames`() {
        var now = 0L
        val recognizer = ScriptedRecognizer(generateSequence { l1 to l2.replaceRange(8, 9, "8") }.iterator())
        val pipeline = FramePipeline(
            engine(FakeDetector(Detection(bandQuad, 0.9f)), recognizer),
            readSession = ReadSession(rule = StoppingRule(StoppingRule.Config(timeoutMs = 1_000))),
            clock = { now },
        )
        now = 100_000_000L
        pipeline.process(frame(now))
        now = 2_000_000_000L
        val e = pipeline.tick()
        val d = assertIs<FramePipeline.Event.Done>(e)
        assertIs<MrzReadOutcome.Failed>(d.outcome)
    }

    @Test
    fun `rotated frames are brought upright before detection`() {
        val recognizer = ScriptedRecognizer(generateSequence { l1 to l2 }.iterator())
        var seenWidth = -1
        val detector = object : MrzDetector {
            override val inputLongSide = 480
            override fun detect(image: LumaImage): Detection? { seenWidth = image.width; return null }
            override fun close() {}
        }
        val pipeline = FramePipeline(engine(detector, recognizer), readSession = session())
        val portrait = Frame(SyntheticImages.flat(720, 1280, 200), 90, 1)
        pipeline.process(portrait)
        // 720x1280 rotated 90 clockwise is 1280x720; downscaled by 2 for a 480 long side target.
        assertEquals(640, seenWidth)
        assertTrue(Td3Layout.LINE_LENGTH == 44)
    }
}
