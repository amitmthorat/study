package com.airline.mrz.engine.tflite

import com.airline.mrz.engine.Detection
import com.airline.mrz.engine.MrzDetector
import com.airline.mrz.engine.PointF
import com.airline.mrz.engine.Quad
import com.airline.mrz.engine.image.LumaImage
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Quadrilateral MRZ band detector.
 *
 * Model contract (see `models.json`): input `[1, H, W, 1]` float in `0..1`, letterboxed;
 * output `[1, 9]` = confidence followed by four normalised corners clockwise from
 * top-left. Runs a second pass on a region of interest when the first box is small or
 * badly shaped, following the YOLO-MRZ two-pass trick.
 */
public class TfliteMrzDetector(
    registry: ModelRegistry,
    delegates: Delegates,
    private val minConfidence: Float = 0.6f,
    private val roiMinHeightFraction: Float = 0.12f,
    private val roiMarginFraction: Float = 0.25f,
) : MrzDetector {

    private val spec = registry.specs.getValue("mrz_detector")
    private val interpreter = Interpreter(registry.load("mrz_detector"), delegates.options())
    private val inH = spec.inputShape[1]
    private val inW = spec.inputShape[2]
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inH * inW * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(9) }

    override val inputLongSide: Int get() = maxOf(inW, inH)

    override fun detect(image: LumaImage): Detection? {
        val first = run(image, 0, 0, image.width, image.height) ?: return null
        val bandHeight = first.band.height
        val aspect = first.band.width / maxOf(1f, bandHeight)
        val needsRefine = bandHeight < image.height * roiMinHeightFraction || aspect !in 7f..12f
        if (!needsRefine) return first

        val mx = (first.band.width * roiMarginFraction).toInt()
        val my = (bandHeight * roiMarginFraction * 2).toInt()
        val left = (first.band.minX().toInt() - mx).coerceAtLeast(0)
        val top = (first.band.minY().toInt() - my).coerceAtLeast(0)
        val right = (first.band.maxX().toInt() + mx).coerceAtMost(image.width)
        val bottom = (first.band.maxY().toInt() + my).coerceAtMost(image.height)
        if (right - left < 16 || bottom - top < 8) return first
        return run(image, left, top, right, bottom) ?: first
    }

    /** Runs the model over the given sub-rectangle and maps the result back to full image coordinates. */
    private fun run(image: LumaImage, left: Int, top: Int, right: Int, bottom: Int): Detection? {
        val roiW = right - left
        val roiH = bottom - top
        // Letterbox the ROI into the model input preserving aspect ratio.
        val scale = minOf(inW.toFloat() / roiW, inH.toFloat() / roiH)
        val dstW = (roiW * scale).toInt().coerceAtLeast(1)
        val dstH = (roiH * scale).toInt().coerceAtLeast(1)
        val padX = (inW - dstW) / 2
        val padY = (inH - dstH) / 2
        input.rewind()
        for (y in 0 until inH) {
            for (x in 0 until inW) {
                val sx = x - padX
                val sy = y - padY
                val v = if (sx in 0 until dstW && sy in 0 until dstH) {
                    image.sampleBilinear(left + sx / scale, top + sy / scale) / 255f
                } else 0f
                input.putFloat(v)
            }
        }
        input.rewind()
        interpreter.run(input, output)
        val o = output[0]
        if (o[0] < minConfidence) return null
        fun corner(i: Int): PointF {
            val nx = o[1 + i * 2] * inW
            val ny = o[2 + i * 2] * inH
            return PointF(left + (nx - padX) / scale, top + (ny - padY) / scale)
        }
        return Detection(Quad(corner(0), corner(1), corner(2), corner(3)), o[0])
    }

    override fun close() {
        interpreter.close()
    }
}
