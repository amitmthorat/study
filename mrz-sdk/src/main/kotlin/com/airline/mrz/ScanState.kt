package com.airline.mrz

import com.airline.mrz.engine.Hint
import com.airline.mrz.engine.Quad

/**
 * What the scanner is doing right now, for the UI. The band, when present, is in
 * upright-frame pixel coordinates together with the frame size so the overlay can map
 * it onto the preview.
 */
public sealed interface ScanState {
    public object Idle : ScanState

    /** Camera running, no MRZ band in view. */
    public object Searching : ScanState

    /** Band found but the frame was rejected; show [hint]. */
    public data class Aligning(val hint: Hint, val band: Quad, val frameWidth: Int, val frameHeight: Int) : ScanState

    /** Frames are voting. [progress] is 0..1 against the stability target. */
    public data class Reading(
        val band: Quad,
        val frameWidth: Int,
        val frameHeight: Int,
        val progress: Float,
        val checksPassing: Boolean,
        val framesVoted: Int,
    ) : ScanState

    public data class Done(val result: ScanResult) : ScanState
}
