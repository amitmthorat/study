package com.airline.mrz.engine.image

/** Box-filter downscaling used to build the detector input from a full-resolution frame. */
public object Downscaler {

    /**
     * Downscales by an integer [factor] with a box filter. Output dimensions are the
     * floor of the input dimensions divided by [factor].
     */
    public fun downscale(src: LumaImage, factor: Int): LumaImage {
        require(factor >= 1)
        if (factor == 1) return src.copy()
        val w = src.width / factor
        val h = src.height / factor
        require(w > 0 && h > 0) { "factor $factor too large for ${src.width}x${src.height}" }
        val out = ByteArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            val sy0 = y * factor
            for (x in 0 until w) {
                val sx0 = x * factor
                var sum = 0
                for (dy in 0 until factor) {
                    val row = src.offset + (sy0 + dy) * src.rowStride + sx0
                    for (dx in 0 until factor) sum += src.data[row + dx].toInt() and 0xFF
                }
                out[y * w + x] = (sum / area).toByte()
            }
        }
        return LumaImage(out, w, h)
    }

    /** Chooses the integer factor that brings the longer side to at most [targetLongSide]. */
    public fun factorFor(width: Int, height: Int, targetLongSide: Int): Int {
        val long = maxOf(width, height)
        var f = 1
        while (long / (f + 1) >= targetLongSide) f++
        return f
    }
}
