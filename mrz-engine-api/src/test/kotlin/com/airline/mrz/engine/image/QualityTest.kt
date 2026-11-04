package com.airline.mrz.engine.image

import com.airline.mrz.engine.Hint
import com.airline.mrz.engine.Quad
import com.airline.mrz.engine.QualityGate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QualityTest {

    @Test
    fun `sharpness drops with blur`() {
        val sharp = SyntheticImages.textLikeBand(200, 40)
        val soft = SyntheticImages.blurred(sharp, passes = 3)
        assertTrue(Quality.laplacianVariance(sharp) > Quality.laplacianVariance(soft) * 2)
        assertEquals(0f, Quality.laplacianVariance(SyntheticImages.flat(50, 50, 128)))
    }

    @Test
    fun `glare fraction counts only wide saturated runs`() {
        val base = SyntheticImages.flat(200, 40, 200)
        assertEquals(0f, Quality.glareFraction(base, minBlobWidth = 10))
        val streak = SyntheticImages.withGlare(base, top = 10, height = 4) // 4 rows x 200 px = 800 of 8000
        assertEquals(0.1f, Quality.glareFraction(streak, minBlobWidth = 10), 1e-4f)
        val dots = SyntheticImages.withGlare(base, top = 10, height = 4, left = 0, right = 5) // runs of 5 < 10
        assertEquals(0f, Quality.glareFraction(dots, minBlobWidth = 10))
    }

    @Test
    fun `contrast is the 5 to 95 percentile spread`() {
        assertEquals(0, Quality.contrast(SyntheticImages.flat(20, 20, 100)))
        val band = SyntheticImages.textLikeBand(200, 40, background = 230, ink = 20)
        assertTrue(Quality.contrast(band) >= 200)
    }

    @Test
    fun `straight text has no curl and bowed text does`() {
        val straight = SyntheticImages.textLikeBand(400, 32, lines = 1)
        assertTrue(Quality.baselineCurl(straight) < 0.05f)

        // Bow the single line: shift each column's ink vertically by a parabola.
        val bowed = LumaImage.blank(400, 32, 230)
        for (x in 0 until 400) {
            val t = (x - 200) / 200.0
            val shift = (6 * t * t).toInt()
            for (y in 4 + shift until 12 + shift) if ((x / 3) % 2 == 0) bowed.data[y * 400 + x] = 20
        }
        // A 6 px bow over 400 px is about 0.25 per 100 px by definition of the metric.
        assertTrue(Quality.baselineCurl(bowed) > 0.15f)
    }

    @Test
    fun `cell weights are low on glared cells`() {
        val line = SyntheticImages.textLikeBand(880, 32, lines = 1)
        val glared = SyntheticImages.withGlare(line, top = 0, height = 32, left = 100, right = 140) // cells 5 and 6
        val w = Quality.cellWeights(glared, cellWidth = 20, cells = 44)
        assertTrue(w[5] < 0.1f)
        assertTrue(w[20] > 0.5f)
    }

    @Test
    fun `gate orders hints by severity and accepts good frames`() {
        val gate = QualityGate()
        val good = metrics()
        assertIs<QualityGate.Decision.Accept>(gate.evaluate(good))
        assertEquals(Hint.HOLD_STILL, (gate.evaluate(good.copy(sharpness = 1f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.AVOID_GLARE, (gate.evaluate(good.copy(glareFraction = 0.1f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.MOVE_CLOSER, (gate.evaluate(good.copy(bandHeightPx = 20f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.FIT_WHOLE_PASSPORT, (gate.evaluate(good.copy(touchesEdge = true)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.HOLD_FLAT, (gate.evaluate(good.copy(tiltDegrees = 30f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.HOLD_PARALLEL, (gate.evaluate(good.copy(perspectiveRatio = 0.5f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.FLATTEN_PAGE, (gate.evaluate(good.copy(curlPer100Px = 2f)) as QualityGate.Decision.Reject).hint)
        assertEquals(Hint.MORE_LIGHT, (gate.evaluate(good.copy(contrast = 10)) as QualityGate.Decision.Reject).hint)
        // strict mode raises the sharpness bar
        assertIs<QualityGate.Decision.Reject>(gate.evaluate(good.copy(sharpness = 70f), strict = true))
    }

    @Test
    fun `frame weight combines focus glare and confidence`() {
        val m = metrics(sharpness = 200f, glareFraction = 0.01f, detectorConfidence = 0.8f)
        assertEquals(0.5f * 0.99f * 0.8f, m.frameWeight(sharpnessScale = 400f), 1e-5f)
    }

    @Test
    fun `end to end measure on a synthetic band`() {
        val frame = SyntheticImages.flat(1280, 720, 220)
        // paint a text-like band region
        val band = SyntheticImages.textLikeBand(880, 96)
        for (y in 0 until 96) for (x in 0 until 880) frame.data[(400 + y) * 1280 + 200 + x] = band.data[y * 880 + x]
        val quad = Quad.ofRect(200f, 400f, 1080f, 496f)
        val rect = Rectifier().rectify(frame, quad)
        val m = Quality.measure(1280, 720, quad, 0.9f, rect)
        assertTrue(m.sharpness > 60f)
        assertEquals(0f, m.glareFraction)
        assertTrue(!m.touchesEdge)
        assertEquals(96f, m.bandHeightPx, 1e-3f)
        assertIs<QualityGate.Decision.Accept>(QualityGate().evaluate(m))
    }

    private fun metrics(
        sharpness: Float = 300f,
        glareFraction: Float = 0f,
        detectorConfidence: Float = 0.9f,
    ) = QualityMetrics(
        sharpness = sharpness, glareFraction = glareFraction, contrast = 150, bandHeightPx = 96f,
        touchesEdge = false, tiltDegrees = 2f, perspectiveRatio = 1.02f, curlPer100Px = 0.1f,
        detectorConfidence = detectorConfidence,
    )

    @Suppress("unused")
    private val rng = Random(1)
}
