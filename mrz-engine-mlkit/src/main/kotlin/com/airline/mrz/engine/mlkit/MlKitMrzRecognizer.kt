package com.airline.mrz.engine.mlkit

import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.recognize.CharDistribution
import com.airline.mrz.core.recognize.LineHypothesis
import com.airline.mrz.engine.MrzRecognizer
import com.airline.mrz.engine.image.LumaImage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Stand-in recognizer using ML Kit's bundled (offline) Latin text model.
 *
 * ML Kit returns strings, not probabilities, so each cell becomes a one-hot
 * distribution at [confidence]; ML Kit's known weaknesses (`<` runs, OCR-B
 * confusables) are what the integrator and corrector then have to absorb. Use only to
 * exercise the pipeline and establish a baseline for the eval harness.
 */
public class MlKitMrzRecognizer(
    private val confidence: Float = 0.6f,
    /** Upscale factor applied to the 32 px lines; ML Kit prefers taller text. */
    private val upscale: Int = 2,
) : MrzRecognizer {
    override val cellWidth: Int = 20
    override val lineHeight: Int = 32

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(lines: List<LumaImage>, frameWeight: Float, cellWeights: List<FloatArray>): List<LineHypothesis> =
        lines.mapIndexed { i, line ->
            val text = readLine(line)
            LineHypothesis(toCells(text), frameWeight, cellWeights[i])
        }

    private fun readLine(line: LumaImage): String {
        val w = line.width * upscale
        val h = line.height * upscale
        val nv21 = ByteArray(w * h + w * h / 2)
        for (y in 0 until h) for (x in 0 until w) nv21[y * w + x] = line[x / upscale, y / upscale].toByte()
        java.util.Arrays.fill(nv21, w * h, nv21.size, 128.toByte())
        val image = InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)
        val result = try {
            Tasks.await(client.process(image))
        } catch (e: Exception) {
            return ""
        }
        return normalise(result.text)
    }

    /** Keeps only MRZ symbols, mapping the usual ML Kit substitutes for the filler. */
    private fun normalise(raw: String): String = buildString {
        for (c in raw.uppercase()) {
            when {
                c in '0'..'9' || c in 'A'..'Z' -> append(c)
                c == '<' || c == '«' || c == '‹' || c == '(' || c == '[' || c == '{' -> append(MrzAlphabet.FILLER)
                else -> {}
            }
        }
    }

    private fun toCells(text: String): List<CharDistribution> {
        // A read far from 44 characters is not trustworthy; give it near-uniform cells
        // so the structural filter rejects it rather than letting it vote.
        if (text.length !in (Td3Layout.LINE_LENGTH - 2)..(Td3Layout.LINE_LENGTH + 2)) {
            return List(Td3Layout.LINE_LENGTH) { CharDistribution.uniform() }
        }
        val padded = text.take(Td3Layout.LINE_LENGTH).padEnd(Td3Layout.LINE_LENGTH, MrzAlphabet.FILLER)
        return padded.map { CharDistribution.oneHot(it, confidence) }
    }

    override fun close() {
        client.close()
    }
}
