package com.airline.mrz.engine.tflite

import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.engine.MrzRecognizer
import com.airline.mrz.engine.Quad
import com.airline.mrz.engine.VizFields
import com.airline.mrz.engine.VizReader
import com.airline.mrz.engine.image.LumaImage
import com.airline.mrz.engine.image.Rectifier
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads document number, date of birth and date of expiry from the printed visual
 * zone above the MRZ, for cross-checking.
 *
 * The TD3 data page is laid out relative to the MRZ: the search region is the area
 * directly above the band, about three band-heights tall. A small line detector
 * returns one box per field; each box is warped to a canonical line and read with the
 * same calibrated recognizer. Dates printed as `DD MM YYYY`, `DD MMM YYYY` or similar
 * are normalised to the MRZ `YYMMDD` form by [VizDateFormats].
 *
 * The visual zone never overrides the MRZ. A field that cannot be read with a clear
 * margin is returned as null rather than guessed.
 */
public class TfliteVizReader(
    registry: ModelRegistry,
    delegates: Delegates,
    private val recognizer: MrzRecognizer,
    private val rectifier: Rectifier,
    private val minFieldConfidence: Float = 0.5f,
    private val minCharMargin: Float = 0.3f,
) : VizReader {

    private val spec = registry.specs.getValue("viz_line_detector")
    private val interpreter = Interpreter(registry.load("viz_line_detector"), delegates.options())
    private val inH = spec.inputShape[1]
    private val inW = spec.inputShape[2]
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inH * inW * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(3) { FloatArray(5) } }

    override fun read(frame: LumaImage, band: Quad, issuingState: String?): VizFields? {
        // Search region: directly above the band, three band heights tall, same width.
        val bandH = band.height
        val top = (band.minY() - bandH * 3f).toInt().coerceAtLeast(0)
        val bottom = band.minY().toInt().coerceAtMost(frame.height)
        val left = band.minX().toInt().coerceAtLeast(0)
        val right = band.maxX().toInt().coerceAtMost(frame.width)
        if (bottom - top < 16 || right - left < 64) return null
        val regionW = right - left
        val regionH = bottom - top

        input.rewind()
        for (y in 0 until inH) for (x in 0 until inW) {
            input.putFloat(frame.sampleBilinear(left + x * regionW / inW.toFloat(), top + y * regionH / inH.toFloat()) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)

        fun readField(index: Int, alphabet: Set<Char>): String? {
            val o = output[0][index]
            if (o[0] < minFieldConfidence) return null
            val cx = left + o[1] * regionW
            val cy = top + o[2] * regionH
            val w = o[3] * regionW
            val h = o[4] * regionH
            if (w < 8 || h < 4) return null
            // Warp the field box to one canonical line, padding the right side to 44 cells.
            val quad = Quad.ofRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val line = warpToLine(frame, quad)
            val hyp = recognizer.recognize(listOf(line), 1f, listOf(FloatArray(Td3Layout.LINE_LENGTH) { 1f })).firstOrNull() ?: return null
            val text = StringBuilder()
            for (cell in hyp.cells) {
                val r = cell.restrictedTo(alphabet + MrzAlphabet.FILLER)
                val c = r.argmaxChar
                if (c == MrzAlphabet.FILLER) break
                if (r.margin < minCharMargin) return null
                text.append(c)
            }
            return text.toString().ifEmpty { null }
        }

        val docNo = readField(0, MrzAlphabet.LETTERS + MrzAlphabet.DIGITS)
        val dob = readField(1, MrzAlphabet.LETTERS + MrzAlphabet.DIGITS)?.let { VizDateFormats.toYymmdd(it, issuingState) }
        val exp = readField(2, MrzAlphabet.LETTERS + MrzAlphabet.DIGITS)?.let { VizDateFormats.toYymmdd(it, issuingState) }
        if (docNo == null && dob == null && exp == null) return null
        return VizFields(docNo, dob, exp)
    }

    /** Warps a text box into the recognizer's canonical line, keeping the text's aspect and left-aligning it. */
    private fun warpToLine(frame: LumaImage, box: Quad): LumaImage {
        val lineW = rectifier.lineWidth
        val lineH = rectifier.lineHeight
        val scale = lineH / box.height
        val textW = (box.width * scale).toInt().coerceIn(1, lineW)
        val out = LumaImage.blank(lineW, lineH, 255)
        for (y in 0 until lineH) for (x in 0 until textW) {
            val sx = box.minX() + x / scale
            val sy = box.minY() + y / scale
            out.data[y * lineW + x] = frame.sampleBilinear(sx, sy).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    override fun close() {
        interpreter.close()
    }
}

/**
 * Normalises printed date strings to the MRZ `YYMMDD` form. Printed formats vary by
 * issuer; the common ones are handled here and the issuing state can select an
 * override. Returns null when the string cannot be interpreted unambiguously.
 */
public object VizDateFormats {
    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    public fun toYymmdd(raw: String, issuingState: String?): String? {
        val s = raw.uppercase().filter { it.isLetterOrDigit() }
        // DDMMMYYYY (e.g. 12AUG1974) or DDMMMYY
        if (s.length >= 7 && s[2].isLetter()) {
            val dd = s.substring(0, 2)
            val mon = months.indexOfFirst { s.startsWith(it, 2) }
            if (mon < 0 || !dd.all { it.isDigit() }) return null
            val year = s.substring(5)
            val yy = when (year.length) {
                4 -> year.substring(2)
                2 -> year
                else -> return null
            }
            return yy + "%02d".format(mon + 1) + dd
        }
        if (!s.all { it.isDigit() }) return null
        return when (s.length) {
            8 -> {
                // DDMMYYYY by default; a few issuers print YYYYMMDD.
                val yearFirst = issuingState in setOf("CHN", "JPN", "KOR", "HUN", "LTU")
                if (yearFirst) s.substring(2, 4) + s.substring(4, 6) + s.substring(6, 8)
                else s.substring(6, 8) + s.substring(2, 4) + s.substring(0, 2)
            }
            6 -> s.substring(4, 6) + s.substring(2, 4) + s.substring(0, 2) // DDMMYY
            else -> null
        }
    }
}
