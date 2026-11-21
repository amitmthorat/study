package com.airline.mrz.engine.tflite

import android.content.Context
import com.airline.mrz.engine.MrzDetector
import com.airline.mrz.engine.MrzEngine
import com.airline.mrz.engine.MrzRecognizer
import com.airline.mrz.engine.VizReader
import com.airline.mrz.engine.image.Rectifier

/**
 * The production engine: TFLite detector, recognizer and visual-zone reader loaded
 * from the model manifest. Construct once per process on the analysis thread; the
 * interpreters are not thread-safe.
 */
public class TfliteMrzEngine private constructor(
    override val detector: MrzDetector,
    override val recognizer: MrzRecognizer,
    override val vizReader: VizReader?,
    override val rectifier: Rectifier,
    override val modelVersion: String,
    private val delegates: Delegates,
) : MrzEngine {

    public val accelerator: Accelerator get() = delegates.accelerator

    override fun close() {
        super.close()
        delegates.close()
    }

    public companion object {
        /**
         * Loads all models. Throws [ModelLoadException] when a model is missing or its
         * hash does not match; the SDK surfaces that as an engine error, never a
         * silent fallback.
         */
        public fun create(
            context: Context,
            allowGpu: Boolean,
            allowNnapi: Boolean,
            enableVizReader: Boolean = true,
        ): TfliteMrzEngine {
            val registry = ModelRegistry(context.assets)
            val delegates = Delegates(allowGpu, allowNnapi)
            val recognizer = TfliteMrzRecognizer(registry, delegates)
            val rectifier = Rectifier(cellWidth = recognizer.cellWidth, lineHeight = recognizer.lineHeight)
            val detector = TfliteMrzDetector(registry, delegates)
            val viz = if (enableVizReader && registry.specs.containsKey("viz_line_detector")) {
                TfliteVizReader(registry, delegates, recognizer, rectifier)
            } else null
            return TfliteMrzEngine(detector, recognizer, viz, rectifier, registry.versionString, delegates)
        }
    }
}
