package com.airline.mrz.engine.image

import com.airline.mrz.engine.PointF
import com.airline.mrz.engine.Quad
import kotlin.test.Test
import kotlin.test.assertEquals

class HomographyTest {

    @Test
    fun `identity rectangle maps to itself`() {
        val h = Homography.fromRectToQuad(100f, 40f, Quad.ofRect(0f, 0f, 100f, 40f))
        val p = h.map(37f, 11f)
        assertEquals(37f, p.x, 1e-3f)
        assertEquals(11f, p.y, 1e-3f)
    }

    @Test
    fun `corners map exactly onto the quad corners`() {
        val quad = Quad(PointF(120f, 300f), PointF(900f, 280f), PointF(910f, 420f), PointF(110f, 440f))
        val h = Homography.fromRectToQuad(880f, 96f, quad)
        val expected = listOf(PointF(0f, 0f) to quad.topLeft, PointF(880f, 0f) to quad.topRight, PointF(880f, 96f) to quad.bottomRight, PointF(0f, 96f) to quad.bottomLeft)
        for ((src, dst) in expected) {
            val m = h.map(src.x, src.y)
            assertEquals(dst.x, m.x, 1e-2f)
            assertEquals(dst.y, m.y, 1e-2f)
        }
    }

    @Test
    fun `interior points of a pure scale are scaled`() {
        val h = Homography.fromRectToQuad(10f, 10f, Quad.ofRect(0f, 0f, 20f, 40f))
        val m = h.map(5f, 5f)
        assertEquals(10f, m.x, 1e-3f)
        assertEquals(20f, m.y, 1e-3f)
    }
}
