package com.airline.mrz.engine.image

import com.airline.mrz.engine.PointF
import com.airline.mrz.engine.Quad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RectifierTest {

    @Test
    fun `axis aligned band is copied at canonical size`() {
        val frame = SyntheticImages.flat(1000, 300, 200)
        val band = SyntheticImages.textLikeBand(440, 48)
        for (y in 0 until 48) for (x in 0 until 440) frame.data[(100 + y) * 1000 + 100 + x] = band.data[y * 440 + x]
        val r = Rectifier()
        val out = r.rectify(frame, Quad.ofRect(100f, 100f, 540f, 148f))
        assertEquals(880, out.line1.width)
        assertEquals(32, out.line1.height)
        assertEquals(880, out.band.width)
        assertEquals(96, out.band.height)
        // Both extracted lines contain ink; the gap between them does not dominate.
        assertTrue(Quality.contrast(out.line1) > 100)
        assertTrue(Quality.contrast(out.line2) > 100)
    }

    @Test
    fun `tilted band is straightened`() {
        // Draw a band rotated by 10 degrees into a larger frame by inverse mapping.
        val frame = SyntheticImages.flat(1200, 600, 220)
        val band = SyntheticImages.textLikeBand(880, 96)
        val angle = Math.toRadians(10.0)
        val cx = 600.0
        val cy = 300.0
        for (y in 0 until 600) for (x in 0 until 1200) {
            val dx = x - cx
            val dy = y - cy
            val u = dx * Math.cos(angle) + dy * Math.sin(angle) + 440
            val v = -dx * Math.sin(angle) + dy * Math.cos(angle) + 48
            if (u >= 0 && u < 880 && v >= 0 && v < 96) frame.data[y * 1200 + x] = band.data[v.toInt() * 880 + u.toInt()]
        }
        fun corner(u: Double, v: Double): PointF {
            val du = u - 440
            val dv = v - 48
            return PointF((cx + du * Math.cos(angle) - dv * Math.sin(angle)).toFloat(), (cy + du * Math.sin(angle) + dv * Math.cos(angle)).toFloat())
        }
        val quad = Quad(corner(0.0, 0.0), corner(880.0, 0.0), corner(880.0, 96.0), corner(0.0, 96.0))
        assertEquals(10f, quad.tiltDegrees, 0.5f)
        val out = Rectifier().rectify(frame, quad)
        // After rectification the lines are horizontal: curl is tiny and lines are found at the expected rows.
        assertTrue(Quality.baselineCurl(out.line1) < 0.1f)
        val (top1, top2) = Rectifier().locateLines(out.band)
        assertTrue(top2 > top1 + 20)
    }
}
