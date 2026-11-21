package com.airline.mrz.engine.tflite

import com.airline.mrz.core.model.MrzAlphabet
import com.airline.mrz.core.model.Td3Layout
import com.airline.mrz.core.recognize.CharDistribution
import com.airline.mrz.core.recognize.LineHypothesis
import com.airline.mrz.engine.MrzRecognizer
import com.airline.mrz.engine.image.LumaImage
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * 37-class MRZ line recognizer.
 *
 * Model contract: input `[1, 32, 880, 1]` float in `0..1` (one rectified line), output
 * `[1, 44, 37]` logits in [MrzAlphabet] order. Softmax with the manifest's calibration
 * temperature is applied here so the pipeline receives calibrated distributions. This
 * contract fits both the per-cell CNN (v1) and a CRNN whose CTC output has been
 * collapsed to 44 positions (v2).
 */
public class TfliteMrzRecognizer(
    registry: ModelRegistry,
    delegates: Delegates,
) : MrzRecognizer {

    private val spec = registry.specs.getValue("mrz_recognizer")
    private val interpreter = Interpreter(registry.load("mrz_recognizer"), delegates.options())
    override val lineHeight: Int = spec.inputShape[1]
    override val cellWidth: Int = spec.inputShape[2] / Td3Layout.LINE_LENGTH
    private val temperature = spec.temperature
    private val input: ByteBuffer = ByteBuffer.allocateDirect(lineHeight * spec.inputShape[2] * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(Td3Layout.LINE_LENGTH) { FloatArray(MrzAlphabet.SIZE) } }

    init {
        require(spec.outputShape.contentEquals(intArrayOf(1, Td3Layout.LINE_LENGTH, MrzAlphabet.SIZE))) {
            "recognizer output must be [1, 44, 37], manifest says ${spec.outputShape.toList()}"
        }
    }

    override fun recognize(lines: List<LumaImage>, frameWeight: Float, cellWeights: List<FloatArray>): List<LineHypothesis> =
        lines.mapIndexed { i, line ->
            require(line.height == lineHeight && line.width == cellWidth * Td3Layout.LINE_LENGTH) {
                "line must be ${cellWidth * Td3Layout.LINE_LENGTH}x$lineHeight, got ${line.width}x${line.height}"
            }
            fill(line)
            interpreter.run(input, output)
            val cells = List(Td3Layout.LINE_LENGTH) { c -> softmax(output[0][c]) }
            LineHypothesis(cells, frameWeight, cellWeights[i])
        }

    private fun fill(line: LumaImage) {
        input.rewind()
        for (y in 0 until line.height) for (x in 0 until line.width) input.putFloat(line[x, y] / 255f)
        input.rewind()
    }

    private fun softmax(logits: FloatArray): CharDistribution {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - max) / temperature) }
        return CharDistribution.of(exps)
    }

    override fun close() {
        interpreter.close()
    }
}
