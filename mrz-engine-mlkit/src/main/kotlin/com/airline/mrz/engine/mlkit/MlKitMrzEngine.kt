package com.airline.mrz.engine.mlkit

import com.airline.mrz.engine.HeuristicMrzDetector
import com.airline.mrz.engine.MrzDetector
import com.airline.mrz.engine.MrzEngine
import com.airline.mrz.engine.MrzRecognizer
import com.airline.mrz.engine.VizReader
import com.airline.mrz.engine.image.Rectifier

/** Development engine: heuristic band detector plus the ML Kit stand-in recognizer. No VIZ reader. */
public class MlKitMrzEngine : MrzEngine {
    override val detector: MrzDetector = HeuristicMrzDetector()
    override val recognizer: MrzRecognizer = MlKitMrzRecognizer()
    override val vizReader: VizReader? = null
    override val rectifier: Rectifier = Rectifier(cellWidth = recognizer.cellWidth, lineHeight = recognizer.lineHeight)
    override val modelVersion: String = "mlkit-dev"
}
