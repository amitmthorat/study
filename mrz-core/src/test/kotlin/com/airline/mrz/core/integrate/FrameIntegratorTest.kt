package com.airline.mrz.core.integrate

import com.airline.mrz.core.Fixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameIntegratorTest {

    @Test
    fun `empty integrator has no result`() {
        assertNull(FrameIntegrator().integrated())
    }

    @Test
    fun `noisy frames converge to the truth`() {
        val rng = Random(7)
        val integrator = FrameIntegrator()
        repeat(10) {
            // Each frame has two random errors on line 2 with a narrow margin.
            integrator.add(Fixtures.randomlyCorruptedFrame(rng, errors = 2, confidence = 0.8f))
        }
        val result = assertNotNull(integrator.integrated())
        assertEquals(Fixtures.SPECIMEN_L1, result.rawLines[0])
        assertEquals(Fixtures.SPECIMEN_L2, result.rawLines[1])
        assertEquals(10, result.framesVoted)
    }

    @Test
    fun `only the best half of the window contributes`() {
        val integrator = FrameIntegrator(windowSize = 4, keepFraction = 0.5f, minFramesToKeep = 1)
        // Two low-weight frames with a wrong, confident digit, two high-weight clean frames.
        val wrong = Fixtures.SPECIMEN_L2.replaceRange(26, 27, "3")
        integrator.add(Fixtures.frame(l2 = wrong, frameWeight = 0.2f))
        integrator.add(Fixtures.frame(l2 = wrong, frameWeight = 0.2f))
        integrator.add(Fixtures.frame(frameWeight = 0.9f))
        integrator.add(Fixtures.frame(frameWeight = 0.9f))
        val r = assertNotNull(integrator.integrated())
        assertEquals(2, r.framesContributing)
        assertEquals(Fixtures.SPECIMEN_L2, r.rawLines[1])
    }

    @Test
    fun `cell weights down-weight smeared characters`() {
        val integrator = FrameIntegrator(minFramesToKeep = 2)
        val smearedWeights = FloatArray(44) { 1f }.also { it[26] = 0.05f }
        val wrong = Fixtures.SPECIMEN_L2.replaceRange(26, 27, "3")
        // Frame with a confident wrong char at a cell flagged as smeared.
        integrator.add(
            com.airline.mrz.core.recognize.FrameHypothesis(
                Fixtures.noisyLine(Fixtures.SPECIMEN_L1),
                Fixtures.noisyLine(wrong, confidence = 0.99f, cellWeights = smearedWeights),
            ),
        )
        integrator.add(Fixtures.frame(confidence = 0.7f))
        assertEquals(Fixtures.SPECIMEN_L2, integrator.integrated()!!.rawLines[1])
    }

    @Test
    fun `structurally broken frames are rejected and do not vote`() {
        val integrator = FrameIntegrator()
        val r = integrator.add(Fixtures.frame(swaps1 = mapOf(0 to 'V')))
        assertIs<FrameIntegrator.AddResult.Rejected>(r)
        assertNull(integrator.integrated())
    }

    @Test
    fun `a new document resets the accumulator after three confident frames`() {
        val integrator = FrameIntegrator()
        repeat(5) { integrator.add(Fixtures.frame()) }
        val other = Fixtures.SPECIMEN_L2.replaceRange(0, 9, "X12345678")
        assertIs<FrameIntegrator.AddResult.Voted>(integrator.add(Fixtures.frame(l2 = other, confidence = 0.99f)))
        assertIs<FrameIntegrator.AddResult.Voted>(integrator.add(Fixtures.frame(l2 = other, confidence = 0.99f)))
        val third = integrator.add(Fixtures.frame(l2 = other, confidence = 0.99f))
        assertIs<FrameIntegrator.AddResult.ResetAndVoted>(third)
        val r = assertNotNull(integrator.integrated())
        assertEquals(1, r.framesVoted)
        assertEquals("X12345678", r.rawLines[1].substring(0, 9))
    }

    @Test
    fun `a single low-confidence different document does not reset`() {
        val integrator = FrameIntegrator()
        repeat(5) { integrator.add(Fixtures.frame()) }
        val other = Fixtures.SPECIMEN_L2.replaceRange(0, 9, "X12345678")
        repeat(3) { integrator.add(Fixtures.frame(l2 = other, confidence = 0.4f)) }
        assertEquals(8, integrator.integrated()!!.framesVoted)
    }

    @Test
    fun `expected change bound shrinks as frames accumulate`() {
        val integrator = FrameIntegrator(windowSize = 100, keepFraction = 1f)
        integrator.add(Fixtures.frame())
        val one = integrator.expectedChangeBound()
        repeat(9) { integrator.add(Fixtures.frame()) }
        val ten = integrator.expectedChangeBound()
        assertTrue(ten < one)
        assertEquals(1f / 11, ten, 1e-5f)
    }
}
