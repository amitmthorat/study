package com.airline.mrz.camera

import com.airline.mrz.engine.Quad

/**
 * Keeps autofocus and exposure metering on the MRZ band and locks focus once the
 * band has been at a stable size for a few frames, since autofocus hunting is the
 * main reported cause of OCR failure on budget devices. Unlocks when the band is lost.
 */
public class FocusController(
    private val source: FrameSource,
    private val stableFramesToLock: Int = 5,
    private val sizeToleranceFraction: Float = 0.1f,
) {
    private var lastHeight = -1f
    private var stable = 0
    private var locked = false
    private var framesWithoutBand = 0

    /** Call with the band in upright-frame pixel coordinates, or null when no band was found. */
    public fun onBand(band: Quad?, frameWidth: Int, frameHeight: Int) {
        if (band == null) {
            framesWithoutBand++
            if (framesWithoutBand >= 3 && locked) {
                source.setFocusLocked(false); locked = false
            }
            stable = 0
            lastHeight = -1f
            return
        }
        framesWithoutBand = 0
        val cx = ((band.topLeft.x + band.bottomRight.x) / 2f) / frameWidth
        val cy = ((band.topLeft.y + band.bottomRight.y) / 2f) / frameHeight
        if (!locked) source.setMeteringPoint(cx, cy)

        val h = band.height
        if (lastHeight > 0 && kotlin.math.abs(h - lastHeight) <= lastHeight * sizeToleranceFraction) stable++ else stable = 0
        lastHeight = h
        if (!locked && stable >= stableFramesToLock) {
            source.setFocusLocked(true); locked = true
        }
    }

    public fun reset() {
        if (locked) source.setFocusLocked(false)
        locked = false; stable = 0; lastHeight = -1f; framesWithoutBand = 0
    }
}
