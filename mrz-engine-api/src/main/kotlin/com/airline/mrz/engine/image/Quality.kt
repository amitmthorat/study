package com.airline.mrz.engine.image

import com.airline.mrz.engine.Quad

/** Per-frame quality measurements taken on the rectified band and the detection geometry. */
public data class QualityMetrics(
    /** Variance of the Laplacian over the band, normalised by band height. Higher is sharper. */
    val sharpness: Float,
    /** Fraction of band pixels inside saturated blobs at least two cells wide. */
    val glareFraction: Float,
    /** 95th minus 5th percentile of luma in the band. */
    val contrast: Int,
    /** Height of the band in source pixels. */
    val bandHeightPx: Float,
    /** True when any band corner lies within the edge margin of the frame. */
    val touchesEdge: Boolean,
    /** Absolute tilt of the band's top edge in degrees. */
    val tiltDegrees: Float,
    /** Left edge length over right edge length. */
    val perspectiveRatio: Float,
    /** Mean absolute deviation of each line's ink baseline from a straight fit, per 100 px. */
    val curlPer100Px: Float,
    /** Detector confidence passed through for weighting. */
    val detectorConfidence: Float,
) {
    /** Frame weight for the integrator: focus x (1 - glare) x detector confidence, in 0..1. */
    public fun frameWeight(sharpnessScale: Float): Float {
        val focus = (sharpness / sharpnessScale).coerceIn(0f, 1f)
        return (focus * (1f - glareFraction).coerceIn(0f, 1f) * detectorConfidence.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

/** Computes [QualityMetrics]. Pure functions over [LumaImage]; no allocation beyond small arrays. */
public object Quality {

    public fun measure(
        frameWidth: Int,
        frameHeight: Int,
        band: Quad,
        detectorConfidence: Float,
        rectified: Rectifier.RectifiedLines,
        edgeMarginPx: Int = 8,
    ): QualityMetrics = QualityMetrics(
        sharpness = laplacianVariance(rectified.band),
        glareFraction = glareFraction(rectified.band, minBlobWidth = rectified.line1.width / 22),
        contrast = contrast(rectified.band),
        bandHeightPx = band.height,
        touchesEdge = band.corners.any {
            it.x < edgeMarginPx || it.y < edgeMarginPx || it.x > frameWidth - edgeMarginPx || it.y > frameHeight - edgeMarginPx
        },
        tiltDegrees = kotlin.math.abs(band.tiltDegrees),
        perspectiveRatio = band.perspectiveRatio,
        curlPer100Px = (baselineCurl(rectified.line1) + baselineCurl(rectified.line2)) / 2f,
        detectorConfidence = detectorConfidence,
    )

    /** Variance of the 3x3 Laplacian response. */
    public fun laplacianVariance(img: LumaImage): Float {
        if (img.width < 3 || img.height < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until img.height - 1) {
            for (x in 1 until img.width - 1) {
                val l = -4 * img[x, y] + img[x - 1, y] + img[x + 1, y] + img[x, y - 1] + img[x, y + 1]
                sum += l; sumSq += l.toDouble() * l; n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    /**
     * Fraction of pixels that belong to a run of saturated pixels (>= [threshold]) at
     * least [minBlobWidth] wide on its row. Row runs are a cheap stand-in for connected
     * components and catch the horizontal glare streaks that flip digits.
     */
    public fun glareFraction(img: LumaImage, minBlobWidth: Int, threshold: Int = 250): Float {
        var glare = 0
        for (y in 0 until img.height) {
            var run = 0
            for (x in 0..img.width) {
                val sat = x < img.width && img[x, y] >= threshold
                if (sat) run++ else {
                    if (run >= minBlobWidth) glare += run
                    run = 0
                }
            }
        }
        return glare.toFloat() / (img.width * img.height)
    }

    /** 95th minus 5th percentile of the luma histogram. */
    public fun contrast(img: LumaImage): Int {
        val hist = IntArray(256)
        for (y in 0 until img.height) for (x in 0 until img.width) hist[img[x, y]]++
        val total = img.width * img.height
        fun percentile(p: Double): Int {
            val target = (total * p).toInt()
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]; if (acc >= target) return v
            }
            return 255
        }
        return percentile(0.95) - percentile(0.05)
    }

    /**
     * Mean absolute deviation (per 100 px of width) of the per-column ink centroid from
     * the best straight line through it. A curved page shows as a bowed baseline.
     */
    public fun baselineCurl(line: LumaImage, columns: Int = 16): Float {
        val w = line.width
        val h = line.height
        val step = maxOf(1, w / columns)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var x0 = 0
        while (x0 + step <= w) {
            var num = 0.0
            var den = 0.0
            for (y in 0 until h) {
                var ink = 0.0
                for (x in x0 until x0 + step) ink += 255 - line[x, y]
                num += ink * y; den += ink
            }
            if (den > 0) {
                xs += (x0 + step / 2.0); ys += num / den
            }
            x0 += step
        }
        if (xs.size < 3) return 0f
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = if (sxx == 0.0) 0.0 else sxy / sxx
        val intercept = my - slope * mx
        var dev = 0.0
        for (i in 0 until n) dev += kotlin.math.abs(ys[i] - (slope * xs[i] + intercept))
        return (dev / n * 100.0 / w).toFloat()
    }

    /**
     * Per-cell weights for the integrator: local sharpness relative to the line's
     * median, clipped to `0..1`, and zeroed where a cell is mostly saturated.
     */
    public fun cellWeights(line: LumaImage, cellWidth: Int, cells: Int): FloatArray {
        val sharp = FloatArray(cells)
        val glare = FloatArray(cells)
        for (i in 0 until cells) {
            val cell = line.crop(i * cellWidth, 0, (i + 1) * cellWidth, line.height)
            sharp[i] = laplacianVariance(cell)
            glare[i] = glareFraction(cell, minBlobWidth = maxOf(2, cellWidth / 2))
        }
        val median = sharp.sorted()[cells / 2].coerceAtLeast(1e-3f)
        return FloatArray(cells) { i ->
            val s = (sharp[i] / median).coerceIn(0f, 1f)
            val g = if (glare[i] > 0.3f) 0f else 1f - glare[i]
            (s * g).coerceIn(0.05f, 1f)
        }
    }
}
