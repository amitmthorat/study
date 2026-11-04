package com.airline.mrz.core.integrate

import com.airline.mrz.core.Fixtures
import com.airline.mrz.core.model.Field
import com.airline.mrz.core.parse.Td3Parser
import com.airline.mrz.core.result.FailureReason
import com.airline.mrz.core.result.MrzReadOutcome
import com.airline.mrz.core.result.VizCrossCheck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StoppingRuleTest {
    private fun session(config: StoppingRule.Config = StoppingRule.Config()) =
        ReadSession(FrameIntegrator(), StoppingRule(config, Td3Parser(Fixtures.dates)))

    @Test
    fun `clean frames verify once minFrames and stability are met`() {
        val s = session()
        var decision: StoppingRule.Decision? = null
        for (i in 1..10) {
            decision = s.onFrame(Fixtures.frame(), VizCrossCheck.NotRead, elapsedMs = i * 100L).second
            if (decision is StoppingRule.Decision.Stop) {
                assertEquals(5, i, "should stop exactly when minFrames is reached")
                break
            }
        }
        val stop = assertIs<StoppingRule.Decision.Stop>(decision)
        val verified = assertIs<MrzReadOutcome.Verified>(stop.outcome)
        assertEquals("L898902C3", verified.passport.documentNumber)
        assertTrue(verified.read.allChecksPass)
        assertEquals(5, verified.read.framesVoted)
    }

    @Test
    fun `noisy frames eventually verify with the true content`() {
        val rng = Random(3)
        val s = session()
        var outcome: MrzReadOutcome? = null
        for (i in 1..40) {
            val d = s.onFrame(Fixtures.randomlyCorruptedFrame(rng, errors = 2, confidence = 0.8f), VizCrossCheck.NotRead, i * 100L).second
            if (d is StoppingRule.Decision.Stop) {
                outcome = d.outcome; break
            }
        }
        val v = assertIs<MrzReadOutcome.Verified>(outcome)
        assertEquals(Fixtures.SPECIMEN_L2, v.read.correctedLine2)
    }

    @Test
    fun `low margin blocks verification`() {
        val s = session(StoppingRule.Config(minMargin = 0.9f))
        for (i in 1..10) {
            val d = s.onFrame(Fixtures.frame(confidence = 0.8f), VizCrossCheck.NotRead, i * 100L).second
            assertIs<StoppingRule.Decision.Continue>(d)
        }
    }

    @Test
    fun `a persistent check digit failure never verifies`() {
        val wrong = Fixtures.SPECIMEN_L2.replaceRange(8, 9, "8") // confident wrong document number char
        val s = session()
        for (i in 1..10) {
            val d = s.onFrame(Fixtures.frame(l2 = wrong, confidence = 0.99f), VizCrossCheck.NotRead, i * 100L).second
            val c = assertIs<StoppingRule.Decision.Continue>(d)
            assertTrue(!c.progress.checksPassing)
        }
    }

    @Test
    fun `timeout fails by default and returns unverified when allowed`() {
        val wrong = Fixtures.SPECIMEN_L2.replaceRange(8, 9, "8")
        val strict = session(StoppingRule.Config(timeoutMs = 1_000))
        repeat(5) { strict.onFrame(Fixtures.frame(l2 = wrong, confidence = 0.99f), VizCrossCheck.NotRead, 100L) }
        val d = strict.onTick(VizCrossCheck.NotRead, elapsedMs = 1_000)
        val stop = assertIs<StoppingRule.Decision.Stop>(d)
        val failed = assertIs<MrzReadOutcome.Failed>(stop.outcome)
        assertEquals(FailureReason.TIMEOUT, failed.reason)

        val lenient = session(StoppingRule.Config(timeoutMs = 1_000, allowUnverifiedResult = true))
        repeat(5) { lenient.onFrame(Fixtures.frame(l2 = wrong, confidence = 0.99f), VizCrossCheck.NotRead, 100L) }
        val d2 = lenient.onTick(VizCrossCheck.NotRead, elapsedMs = 1_000)
        val unverified = assertIs<MrzReadOutcome.Unverified>(assertIs<StoppingRule.Decision.Stop>(d2).outcome)
        assertEquals(FailureReason.TIMEOUT, unverified.reason)
        assertEquals("L898902C8", unverified.passport?.documentNumber)
    }

    @Test
    fun `visual zone disagreement blocks then ends as unverified`() {
        val s = session()
        val viz = VizCrossCheck.Disagrees(setOf(Field.DOCUMENT_NUMBER))
        var last: StoppingRule.Decision? = null
        for (i in 1..12) {
            last = s.onFrame(Fixtures.frame(), viz, i * 100L).second
            if (last is StoppingRule.Decision.Stop) break
        }
        val stop = assertIs<StoppingRule.Decision.Stop>(last)
        val u = assertIs<MrzReadOutcome.Unverified>(stop.outcome)
        assertEquals(FailureReason.VIZ_MISMATCH, u.reason)
    }

    @Test
    fun `visual zone agreement does not delay verification`() {
        val s = session()
        var stopAt = -1
        for (i in 1..10) {
            if (s.onFrame(Fixtures.frame(), VizCrossCheck.Agrees, i * 100L).second is StoppingRule.Decision.Stop) {
                stopAt = i; break
            }
        }
        assertEquals(5, stopAt)
    }
}
