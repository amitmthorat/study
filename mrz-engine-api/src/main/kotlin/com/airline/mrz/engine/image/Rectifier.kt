package com.airline.mrz.engine.image

import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.engine.Quad

/**
 * Warps the detected MRZ band to a canonical, fronto-parallel strip and splits it into
 * two line images of fixed size, so the recognizer always sees characters at the same
 * scale.
 *
 * Canonical geometry: each line is [Td3Layout.LINE_LENGTH] cells of [cellWidth] x
 * [lineHeight] pixels. The band is warped as a whole (two lines plus the gap between
 * them), then each line is cut out using a horizontal projection profile so a slightly
 * off-centre band still yields centred lines.
 */
public class Rectifier(
    public val cellWidth: Int = 20,
    public val lineHeight: Int = 32,
    /** Height of the warped band, in canonical pixels, before line extraction. */
    private val bandHeight: Int = 96,
) {
    public val lineWidth: Int get() = cellWidth * Td3Layout.LINE_LENGTH

    public class RectifiedLines(
        public val line1: LumaImage,
        public val line2: LumaImage,
        /** The full warped band, for quality measurement. */
        public val band: LumaImage,
    )

    public fun rectify(src: LumaImage, band: Quad): RectifiedLines {
        val w = lineWidth
        val h = bandHeight
        val hmg = Homography.fromRectToQuad(w.toFloat(), h.toFloat(), band)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val fy = y + 0.5f
            for (x in 0 until w) {
                val p = hmg.map(x + 0.5f, fy)
                out[y * w + x] = src.sampleBilinear(p.x, p.y).toInt().coerceIn(0, 255).toByte()
            }
        }
        val warped = LumaImage(out, w, h)
        val (top1, top2) = locateLines(warped)
        return RectifiedLines(extractLine(warped, top1), extractLine(warped, top2), warped)
    }

    /**
     * Finds the vertical centre of the two text lines from the row-wise ink profile
     * (dark pixels per row), then returns the top row of each canonical line window.
     * Falls back to an even split when the profile is flat.
     */
    internal fun locateLines(band: LumaImage): Pair<Int, Int> {
        val h = band.height
        val profile = DoubleArray(h)
        var mean = 0.0
        for (y in 0 until h) {
            var s = 0.0
            for (x in 0 until band.width) s += 255 - band[x, y]
            profile[y] = s; mean += s
        }
        mean /= h
        val half = h / 2
        val c1 = weightedCentre(profile, 0, half, mean)
        val c2 = weightedCentre(profile, half, h, mean)
        val top1 = (c1 - lineHeight / 2).coerceIn(0, h - lineHeight)
        val top2 = (c2 - lineHeight / 2).coerceIn(0, h - lineHeight)
        return top1 to top2
    }

    private fun weightedCentre(profile: DoubleArray, from: Int, to: Int, threshold: Double): Int {
        var num = 0.0
        var den = 0.0
        for (y in from until to) {
            val v = profile[y] - threshold
            if (v > 0) {
                num += v * y; den += v
            }
        }
        return if (den <= 0.0) (from + to) / 2 else (num / den).toInt()
    }

    private fun extractLine(band: LumaImage, top: Int): LumaImage = band.crop(0, top, band.width, top + lineHeight).copy()

    /** The canonical cell rectangle for position [index] on a rectified line. */
    public fun cellBounds(index: Int): IntRange = (index * cellWidth) until ((index + 1) * cellWidth)
}
