package com.airline.mrz.engine.image

import com.airline.mrz.engine.PointF
import com.airline.mrz.engine.Quad

/**
 * A 3x3 projective transform, row-major. Maps destination (canonical) coordinates to
 * source (frame) coordinates when built with [fromQuadToRect], which is the direction a
 * warp needs: for every output pixel, where do I sample in the input?
 */
public class Homography(private val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    public fun map(x: Float, y: Float): PointF {
        val X = m[0] * x + m[1] * y + m[2]
        val Y = m[3] * x + m[4] * y + m[5]
        val W = m[6] * x + m[7] * y + m[8]
        return PointF((X / W).toFloat(), (Y / W).toFloat())
    }

    public fun toArray(): DoubleArray = m.copyOf()

    public companion object {
        /**
         * Builds the transform that maps the rectangle `(0,0)-(width,height)` onto [quad],
         * so that `map(u, v)` for a canonical pixel returns the source position to sample.
         */
        public fun fromRectToQuad(width: Float, height: Float, quad: Quad): Homography {
            val src = listOf(PointF(0f, 0f), PointF(width, 0f), PointF(width, height), PointF(0f, height))
            return solve(src, quad.corners)
        }

        /**
         * Direct linear transform for four point correspondences: finds H with
         * `dst_i ~ H * src_i`. Solved as an 8x8 linear system with h33 = 1.
         */
        public fun solve(src: List<PointF>, dst: List<PointF>): Homography {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i].x.toDouble()
                val y = src[i].y.toDouble()
                val u = dst[i].x.toDouble()
                val v = dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = gaussianElimination(a)
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Solves the augmented 8x9 system with partial pivoting. */
        private fun gaussianElimination(a: Array<DoubleArray>): DoubleArray {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
                require(kotlin.math.abs(a[pivot][col]) > 1e-12) { "degenerate quadrilateral" }
                if (pivot != col) {
                    val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                }
                val p = a[col][col]
                for (c in col until n + 1) a[col][c] /= p
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col]
                    if (f == 0.0) continue
                    for (c in col until n + 1) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(n) { a[it][n] }
        }
    }
}
