package com.airline.mrz.engine.image

import kotlin.random.Random

/** Builds small synthetic luma images for engine tests. */
object SyntheticImages {

    /** White background with [rows] evenly spaced dark horizontal bars inside the given band. */
    fun textLikeBand(width: Int, height: Int, lines: Int = 2, background: Int = 230, ink: Int = 20): LumaImage {
        val img = LumaImage.blank(width, height, background)
        val lineH = height / (lines * 2 + 1)
        for (l in 0 until lines) {
            val top = lineH * (2 * l + 1)
            for (y in top until top + lineH) for (x in 0 until width) {
                // alternate ink columns to create edges (text-like high frequency)
                if ((x / 3) % 2 == 0) img.data[y * width + x] = ink.toByte()
            }
        }
        return img
    }

    fun flat(width: Int, height: Int, value: Int): LumaImage = LumaImage.blank(width, height, value)

    fun noise(width: Int, height: Int, rng: Random, base: Int = 128, amplitude: Int = 100): LumaImage {
        val img = LumaImage.blank(width, height)
        for (i in img.data.indices) img.data[i] = (base + rng.nextInt(-amplitude, amplitude + 1)).coerceIn(0, 255).toByte()
        return img
    }

    /** Paints a saturated horizontal streak across the image. */
    fun withGlare(img: LumaImage, top: Int, height: Int, left: Int = 0, right: Int = img.width): LumaImage {
        val out = img.copy()
        for (y in top until minOf(top + height, out.height)) for (x in left until minOf(right, out.width)) out.data[y * out.width + x] = 255.toByte()
        return out
    }

    /** Blurs with a 3x3 box filter, [passes] times. */
    fun blurred(img: LumaImage, passes: Int): LumaImage {
        var cur = img.copy()
        repeat(passes) {
            val next = LumaImage.blank(cur.width, cur.height)
            for (y in 0 until cur.height) for (x in 0 until cur.width) {
                var s = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until cur.width && yy in 0 until cur.height) {
                        s += cur[xx, yy]; n++
                    }
                }
                next.data[y * cur.width + x] = (s / n).toByte()
            }
            cur = next
        }
        return cur
    }
}
