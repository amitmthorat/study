package com.airline.mrz.engine

import com.airline.mrz.engine.image.Rotate
import com.airline.mrz.engine.image.SyntheticImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeuristicMrzDetectorTest {
    private val detector = HeuristicMrzDetector()

    @Test
    fun `finds two text-like lines in the lower part of a frame`() {
        val img = SyntheticImages.flat(480, 270, 215)
        val band = SyntheticImages.textLikeBand(360, 40) // two lines of 8 px each
        for (y in 0 until 40) for (x in 0 until 360) img.data[(200 + y) * 480 + 60 + x] = band.data[y * 360 + x]
        val d = assertNotNull(detector.detect(img))
        assertTrue(d.band.minX() in 40f..70f, "left ${d.band.minX()}")
        assertTrue(d.band.maxX() in 410f..440f, "right ${d.band.maxX()}")
        assertTrue(d.band.minY() in 195f..212f, "top ${d.band.minY()}")
        assertTrue(d.band.maxY() in 228f..245f, "bottom ${d.band.maxY()}")
        assertTrue(d.confidence in 0.5f..0.9f)
    }

    @Test
    fun `flat and noisy frames yield nothing`() {
        assertNull(detector.detect(SyntheticImages.flat(480, 270, 128)))
        val single = SyntheticImages.flat(480, 270, 215)
        val line = SyntheticImages.textLikeBand(360, 24, lines = 1)
        for (y in 0 until 24) for (x in 0 until 360) single.data[(200 + y) * 480 + 60 + x] = line.data[y * 360 + x]
        assertNull(detector.detect(single), "a single line is not an MRZ")
    }

    @Test
    fun `rotation helper round trips`() {
        val img = SyntheticImages.textLikeBand(30, 10)
        val back = Rotate.clockwise(Rotate.clockwise(img, 90), 270)
        assertEquals(img.width, back.width)
        for (y in 0 until img.height) for (x in 0 until img.width) assertEquals(img[x, y], back[x, y])
        val twice = Rotate.clockwise(Rotate.clockwise(img, 180), 180)
        for (y in 0 until img.height) for (x in 0 until img.width) assertEquals(img[x, y], twice[x, y])
    }
}
