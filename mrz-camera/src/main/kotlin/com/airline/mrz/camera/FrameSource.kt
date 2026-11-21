package com.airline.mrz.camera

import com.airline.mrz.engine.Frame

/**
 * Delivers frames to one analyzer on a single background thread.
 *
 * A callback rather than a Flow on purpose: the camera's keep-only-latest
 * backpressure only works when the frame is consumed synchronously and its buffer
 * released on return. The [Frame] passed to [FrameAnalyzer.analyze] is valid only for
 * the duration of the call.
 */
public interface FrameSource {
    public fun start(analyzer: FrameAnalyzer)
    public fun stop()

    /** Points autofocus and exposure metering at a normalised position (0..1) in the upright frame. */
    public fun setMeteringPoint(x: Float, y: Float)

    /** Locks focus at the current distance (true) or returns to continuous autofocus (false). */
    public fun setFocusLocked(locked: Boolean)

    public val torchAvailable: Boolean
    public fun setTorch(enabled: Boolean)
}

public fun interface FrameAnalyzer {
    public fun analyze(frame: Frame)
}
