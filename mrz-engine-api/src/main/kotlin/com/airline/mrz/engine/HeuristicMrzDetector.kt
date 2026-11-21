package com.airline.mrz.engine

import com.airline.mrz.engine.image.LumaImage

/**
 * A model-free MRZ band locator based on edge-density profiles.
 *
 * It finds two horizontal bands of high horizontal-gradient density with similar
 * height and a small gap between them, which is what two lines of OCR-B look like at
 * low resolution. It is used by the development engine (ML Kit stand-in) and as the
 * guidance-only fallback when the learned detector fails for many frames. It is not
 * accurate enough to drive recognition in production.
 */
public class HeuristicMrzDetector(
    override val inputLongSide: Int = 480,
    private val gradientThreshold: Int = 40,
    private val minLineHeightPx: Int = 6,
) : MrzDetector {

    override fun detect(image: LumaImage): Detection? {
        val h = image.height
        val w = image.width
        if (h < minLineHeightPx * 4 || w < 32) return null

        val rowEnergy = IntArray(h)
        for (y in 0 until h) {
            var e = 0
            var prev = image[0, y]
            for (x in 1 until w) {
                val v = image[x, y]
                if (kotlin.math.abs(v - prev) > gradientThreshold) e++
                prev = v
            }
            rowEnergy[y] = e
        }
        val smoothed = IntArray(h) { y ->
            var s = 0
            var n = 0
            for (d in -1..1) {
                val yy = y + d
                if (yy in 0 until h) {
                    s += rowEnergy[yy]; n++
                }
            }
            s / n
        }
        val max = smoothed.max()
        if (max < w / 20) return null
        val threshold = max / 2

        // Group rows above threshold into bands.
        val bands = ArrayList<IntRange>()
        var start = -1
        for (y in 0 until h) {
            val on = smoothed[y] >= threshold
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                if (y - start >= minLineHeightPx) bands += start until y
                start = -1
            }
        }
        if (start >= 0 && h - start >= minLineHeightPx) bands += start until h
        if (bands.size < 2) return null

        // Find the best pair of adjacent bands with similar height and a plausible gap.
        var best: Pair<IntRange, IntRange>? = null
        var bestScore = 0.0
        for (i in 0 until bands.size - 1) {
            val a = bands[i]
            val b = bands[i + 1]
            val ha = a.last - a.first + 1
            val hb = b.last - b.first + 1
            val ratio = minOf(ha, hb).toDouble() / maxOf(ha, hb)
            val gap = b.first - a.last - 1
            val meanH = (ha + hb) / 2.0
            if (ratio < 0.5 || gap < 0 || gap > meanH * 1.5) continue
            var energy = 0.0
            for (y in a) energy += smoothed[y]
            for (y in b) energy += smoothed[y]
            // Prefer bands in the lower part of the image, where the MRZ sits on a data page.
            val positionBonus = 0.5 + 0.5 * (b.last.toDouble() / h)
            val score = energy * ratio * positionBonus
            if (score > bestScore) {
                bestScore = score; best = a to b
            }
        }
        val (a, b) = best ?: return null

        // Horizontal extent from the column profile within the two bands.
        val colEnergy = IntArray(w)
        for (y in a.first..b.last) {
            var prev = image[0, y]
            for (x in 1 until w) {
                val v = image[x, y]
                if (kotlin.math.abs(v - prev) > gradientThreshold) colEnergy[x]++
                prev = v
            }
        }
        val colMax = colEnergy.max()
        if (colMax == 0) return null
        val colThreshold = maxOf(1, colMax / 5)
        var left = 0
        while (left < w && colEnergy[left] < colThreshold) left++
        var right = w - 1
        while (right > left && colEnergy[right] < colThreshold) right--
        if (right - left < w / 4) return null

        val lineH = ((a.last - a.first + 1) + (b.last - b.first + 1)) / 2f
        val pad = lineH * 0.25f
        val top = (a.first - pad).coerceAtLeast(0f)
        val bottom = (b.last + 1 + pad).coerceAtMost(h.toFloat())
        val l = (left - pad).coerceAtLeast(0f)
        val r = (right + 1 + pad).coerceAtMost(w.toFloat())
        val confidence = (0.5 + 0.4 * (bestScore / (max.toDouble() * (a.count() + b.count())))).coerceIn(0.5, 0.9).toFloat()
        return Detection(Quad.ofRect(l, top, r, bottom), confidence)
    }

    override fun close() {}
}
