package com.airline.mrz.engine.image

/** Rotation of a luma image by multiples of 90 degrees, into a new packed buffer. */
public object Rotate {

    /** Rotates clockwise by [degrees] (0, 90, 180, 270). 0 returns a packed copy. */
    public fun clockwise(src: LumaImage, degrees: Int): LumaImage = when (degrees) {
        0 -> src.copy()
        90 -> rotate90(src)
        180 -> rotate180(src)
        270 -> rotate270(src)
        else -> throw IllegalArgumentException("rotation must be a multiple of 90, got $degrees")
    }

    private fun rotate90(src: LumaImage): LumaImage {
        val w = src.height
        val h = src.width
        val out = ByteArray(w * h)
        for (y in 0 until src.height) {
            val row = src.offset + y * src.rowStride
            val dx = w - 1 - y
            for (x in 0 until src.width) out[x * w + dx] = src.data[row + x]
        }
        return LumaImage(out, w, h)
    }

    private fun rotate180(src: LumaImage): LumaImage {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = src.offset + y * src.rowStride
            val dy = h - 1 - y
            for (x in 0 until w) out[dy * w + (w - 1 - x)] = src.data[row + x]
        }
        return LumaImage(out, w, h)
    }

    private fun rotate270(src: LumaImage): LumaImage {
        val w = src.height
        val h = src.width
        val out = ByteArray(w * h)
        for (y in 0 until src.height) {
            val row = src.offset + y * src.rowStride
            for (x in 0 until src.width) out[(h - 1 - x) * w + y] = src.data[row + x]
        }
        return LumaImage(out, w, h)
    }
}
