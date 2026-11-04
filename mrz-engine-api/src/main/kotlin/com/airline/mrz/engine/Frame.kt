package com.airline.mrz.engine

import com.airline.mrz.engine.image.LumaImage

/**
 * One camera frame as the engine sees it: the luma plane only, plus the rotation
 * needed to bring it upright and a monotonic timestamp.
 *
 * Chroma planes are never copied out of the camera buffer; the MRZ is black on white.
 * [rotationDegrees] is the clockwise rotation that makes the image upright with respect
 * to the passport guide in the UI (0, 90, 180 or 270).
 */
public class Frame(
    public val luma: LumaImage,
    public val rotationDegrees: Int,
    public val timestampNanos: Long,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotation must be a multiple of 90" }
    }
}

/** A convex quadrilateral in image pixel coordinates, corners in clockwise order from top-left. */
public data class Quad(
    public val topLeft: PointF,
    public val topRight: PointF,
    public val bottomRight: PointF,
    public val bottomLeft: PointF,
) {
    public val corners: List<PointF> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Mean of the top and bottom edge lengths. */
    public val width: Float get() = (topLeft.distanceTo(topRight) + bottomLeft.distanceTo(bottomRight)) / 2f

    /** Mean of the left and right edge lengths. */
    public val height: Float get() = (topLeft.distanceTo(bottomLeft) + topRight.distanceTo(bottomRight)) / 2f

    /** Angle of the top edge in degrees, 0 when horizontal. */
    public val tiltDegrees: Float
        get() = Math.toDegrees(kotlin.math.atan2((topRight.y - topLeft.y).toDouble(), (topRight.x - topLeft.x).toDouble())).toFloat()

    /** Ratio of the left edge to the right edge: 1 when fronto-parallel. */
    public val perspectiveRatio: Float
        get() {
            val l = topLeft.distanceTo(bottomLeft)
            val r = topRight.distanceTo(bottomRight)
            return if (r == 0f) Float.POSITIVE_INFINITY else l / r
        }

    public fun scaled(sx: Float, sy: Float): Quad = Quad(
        topLeft.scaled(sx, sy), topRight.scaled(sx, sy), bottomRight.scaled(sx, sy), bottomLeft.scaled(sx, sy),
    )

    public fun minX(): Float = corners.minOf { it.x }
    public fun maxX(): Float = corners.maxOf { it.x }
    public fun minY(): Float = corners.minOf { it.y }
    public fun maxY(): Float = corners.maxOf { it.y }

    public companion object {
        public fun ofRect(left: Float, top: Float, right: Float, bottom: Float): Quad =
            Quad(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom))
    }
}

public data class PointF(public val x: Float, public val y: Float) {
    public fun distanceTo(o: PointF): Float {
        val dx = x - o.x
        val dy = y - o.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    public fun scaled(sx: Float, sy: Float): PointF = PointF(x * sx, y * sy)
}

/** Output of the MRZ detector for one frame. */
public data class Detection(
    /** The MRZ band (both lines together) in [Frame] pixel coordinates. */
    public val band: Quad,
    public val confidence: Float,
)
