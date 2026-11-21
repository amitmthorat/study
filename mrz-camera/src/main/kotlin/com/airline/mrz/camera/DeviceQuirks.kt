package com.airline.mrz.camera

import android.os.Build

/**
 * Per-device overrides discovered in the device lab. Keep this table small and every
 * entry justified by a measured failure; it is the place accuracy regressions on
 * specific hardware get fixed without touching the pipeline.
 */
public data class DeviceQuirks(
    /** Reject analysis sizes below this many pixels (1280x720 by default). */
    val minAnalysisPixels: Int = 1280 * 720,
    /** Some SoCs run TFLite slower or incorrectly on NNAPI; deny it there. */
    val allowNnapi: Boolean = true,
    /** Deny the GPU delegate on devices where it is slower than XNNPACK or crashes. */
    val allowGpuDelegate: Boolean = true,
) {
    public companion object {
        private val overrides: Map<String, DeviceQuirks> = mapOf(
            // Populated from the device lab. Keys are Build.MODEL values.
            // "SM-A035F" to DeviceQuirks(allowGpuDelegate = false),
        )

        public fun forThisDevice(): DeviceQuirks = forModel(Build.MODEL)

        public fun forModel(model: String): DeviceQuirks = overrides[model] ?: DeviceQuirks()
    }
}
