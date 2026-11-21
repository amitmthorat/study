package com.airline.mrz.engine.tflite

import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter

/** Which accelerator the interpreters ended up on, for diagnostics. */
public enum class Accelerator { GPU, NNAPI, CPU_XNNPACK }

/**
 * Builds interpreter options with the best available delegate, probing once and
 * caching the decision. CPU with XNNPACK is the floor and must meet the frame budget
 * on the lowest supported tier; GPU is used only when the optional
 * `tensorflow-lite-gpu` artifact is on the classpath and the device quirks allow it.
 */
public class Delegates(
    private val allowGpu: Boolean,
    private val allowNnapi: Boolean,
    private val threads: Int = 2,
) {
    public var accelerator: Accelerator = Accelerator.CPU_XNNPACK
        private set

    private var gpuDelegate: Delegate? = null

    public fun options(): Interpreter.Options {
        val opts = Interpreter.Options()
        if (allowGpu) {
            val gpu = tryGpuDelegate()
            if (gpu != null) {
                opts.addDelegate(gpu)
                accelerator = Accelerator.GPU
                return opts
            }
        }
        if (allowNnapi) {
            // NNAPI is deprecated from Android 15 and varies wildly by vendor; the quirks
            // table decides per device. It is opt-in through the quirk, default on.
            try {
                opts.setUseNNAPI(true)
                accelerator = Accelerator.NNAPI
                return opts
            } catch (_: Throwable) {
            }
        }
        opts.setUseXNNPACK(true)
        opts.setNumThreads(threads)
        accelerator = Accelerator.CPU_XNNPACK
        return opts
    }

    private fun tryGpuDelegate(): Delegate? {
        if (gpuDelegate != null) return gpuDelegate
        return try {
            val cls = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            (cls.getDeclaredConstructor().newInstance() as Delegate).also { gpuDelegate = it }
        } catch (_: Throwable) {
            null
        }
    }

    public fun close() {
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
