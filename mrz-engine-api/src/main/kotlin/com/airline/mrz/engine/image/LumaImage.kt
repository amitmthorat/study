package com.airline.mrz.engine.image

/**
 * An 8-bit single-channel image. [data] holds rows of [width] pixels, each row starting
 * [rowStride] bytes after the previous one (a camera Y plane may be padded). Pixel
 * values are read as unsigned.
 *
 * Instances do not own [data]; a camera frame source reuses buffers. Anything that
 * must outlive the frame copies with [copy].
 */
public class LumaImage(
    public val data: ByteArray,
    public val width: Int,
    public val height: Int,
    public val rowStride: Int = width,
    public val offset: Int = 0,
) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(rowStride >= width) { "rowStride < width" }
        require(offset + (height - 1) * rowStride + width <= data.size) { "buffer too small for ${width}x$height stride $rowStride" }
    }

    public operator fun get(x: Int, y: Int): Int = data[offset + y * rowStride + x].toInt() and 0xFF

    /** Compacts to a tightly packed copy. */
    public fun copy(): LumaImage {
        val out = ByteArray(width * height)
        for (y in 0 until height) System.arraycopy(data, offset + y * rowStride, out, y * width, width)
        return LumaImage(out, width, height)
    }

    /** A view (no copy) of a rectangular region; the region is clamped to the image. */
    public fun crop(left: Int, top: Int, right: Int, bottom: Int): LumaImage {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        return LumaImage(data, r - l, b - t, rowStride, offset + t * rowStride + l)
    }

    /** Overwrites the backing bytes this image covers with zeros. */
    public fun zero() {
        for (y in 0 until height) java.util.Arrays.fill(data, offset + y * rowStride, offset + y * rowStride + width, 0)
    }

    /** Bilinear sample at a sub-pixel position, clamped at the border. */
    public fun sampleBilinear(fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (width - 1).toFloat())
        val y = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val ax = x - x0
        val ay = y - y0
        val p00 = this[x0, y0]
        val p10 = this[x1, y0]
        val p01 = this[x0, y1]
        val p11 = this[x1, y1]
        val top = p00 + (p10 - p00) * ax
        val bottom = p01 + (p11 - p01) * ax
        return top + (bottom - top) * ay
    }

    public companion object {
        public fun blank(width: Int, height: Int, value: Int = 0): LumaImage =
            LumaImage(ByteArray(width * height) { value.toByte() }, width, height)
    }
}
