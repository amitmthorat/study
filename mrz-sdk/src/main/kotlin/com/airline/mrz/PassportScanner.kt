package com.airline.mrz

import android.content.Context
import com.airline.mrz.camera.CameraXFrameSource
import com.airline.mrz.camera.DeviceQuirks
import com.airline.mrz.camera.FrameSource
import com.airline.mrz.engine.MrzEngine
import com.airline.mrz.engine.tflite.ModelLoadException
import com.airline.mrz.engine.tflite.TfliteMrzEngine
import com.airline.mrz.license.LicenseValidator
import java.io.Closeable

/**
 * Entry point. Create once per process: it loads the models and probes accelerators,
 * which is the expensive part. Sessions are cheap.
 *
 * ```
 * val scanner = PassportScanner.create(context, config, licenseJson)
 * val session = scanner.startSession(CameraXFrameSource(context, lifecycleOwner, previewView))
 * session.state.collect { render(it) }
 * val result = session.await()
 * ```
 */
public class PassportScanner private constructor(
    private val context: Context,
    private val config: ScanConfig,
    private val engine: MrzEngine,
    private val acceleratorName: String,
) : Closeable {

    public val modelVersion: String get() = engine.modelVersion

    /** Starts a headless session on any frame source. */
    public fun startSession(frameSource: FrameSource, config: ScanConfig = this.config): ScanSession =
        ScanSession(engine, frameSource, config, BuildInfo.VERSION, acceleratorName).also { it.start() }

    /** Starts a session on the device's back camera, optionally drawing into [previewView]. */
    public fun startCameraSession(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: androidx.camera.view.PreviewView?,
        config: ScanConfig = this.config,
    ): ScanSession = startSession(CameraXFrameSource(context, lifecycleOwner, previewView), config)

    override fun close() {
        engine.close()
    }

    public class LicenseException(message: String) : RuntimeException(message)

    public class EngineInitException(message: String, cause: Throwable?) : RuntimeException(message, cause)

    public companion object {
        /**
         * Builds a scanner. [licenseJson] is validated offline against the embedded public
         * key and the host application id; pass null only in debug builds.
         *
         * @throws LicenseException when the license is invalid for this application.
         * @throws EngineInitException when a model is missing or corrupt.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            context: Context,
            config: ScanConfig = ScanConfig(),
            licenseJson: String? = null,
            licenseValidator: LicenseValidator = LicenseValidator(),
        ): PassportScanner {
            val app = context.applicationContext
            if (licenseJson != null) {
                when (val r = licenseValidator.validate(licenseJson, app.packageName)) {
                    is LicenseValidator.Result.Invalid -> throw LicenseException(r.reason)
                    is LicenseValidator.Result.Valid -> Unit
                }
            } else if (!BuildInfo.DEBUG) {
                throw LicenseException("a license is required in release builds")
            }
            val quirks = DeviceQuirks.forThisDevice()
            val (engine, accel) = when (config.engine) {
                EngineKind.TFLITE -> try {
                    val e = TfliteMrzEngine.create(app, quirks.allowGpuDelegate, quirks.allowNnapi, config.vizCrossCheck)
                    e to e.accelerator.name
                } catch (e: ModelLoadException) {
                    throw EngineInitException(e.message ?: "model load failed", e)
                }
                EngineKind.MLKIT_DEV -> DevEngines.mlKit() to "MLKIT"
            }
            return PassportScanner(app, config, engine, accel)
        }
    }
}

/** Build-time constants. [DEBUG] is true for debug builds of the SDK only. */
internal object BuildInfo {
    const val VERSION: String = "0.1.0"
    val DEBUG: Boolean = com.airline.mrz.BuildConfig.DEBUG
}

/** Loads the development engine by reflection so release builds of the SDK do not depend on ML Kit. */
internal object DevEngines {
    fun mlKit(): MrzEngine = try {
        Class.forName("com.airline.mrz.engine.mlkit.MlKitMrzEngine").getDeclaredConstructor().newInstance() as MrzEngine
    } catch (e: Throwable) {
        throw PassportScanner.EngineInitException("the ML Kit development engine is only available in debug builds", e)
    }
}
