package com.airline.mrz.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.airline.mrz.engine.Frame
import com.airline.mrz.engine.image.LumaImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CameraX-backed [FrameSource].
 *
 * Binds only `Preview` and `ImageAnalysis`: adding `ImageCapture` makes CameraX lower
 * the analysis resolution. Analysis targets 1920x1080 with closest-higher-then-lower
 * fallback and rejects sizes below the device quirk minimum. Backpressure is
 * keep-only-latest on a single analysis thread, so a slow frame is dropped rather than
 * queued.
 */
public class CameraXFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val quirks: DeviceQuirks = DeviceQuirks.forThisDevice(),
) : FrameSource {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mrz-analysis") }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var lumaBuffer: ByteArray = ByteArray(0)
    @Volatile private var focusLocked = false

    override val torchAvailable: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true

    override fun start(analyzer: FrameAnalyzer) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .setResolutionFilter { sizes, _ ->
                    val ok = sizes.filter { it.width * it.height >= quirks.minAnalysisPixels }
                    ok.ifEmpty { sizes }
                }
                .build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            imageAnalysis.setAnalyzer(executor) { proxy -> proxy.use { deliver(it, analyzer) } }
            analysis = imageAnalysis

            val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)
            previewView?.let { view ->
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                useCases += preview
            }
            p.unbindAll()
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        camera = null
        analysis = null
        executor.shutdown()
        executor.awaitTermination(500, TimeUnit.MILLISECONDS)
        java.util.Arrays.fill(lumaBuffer, 0)
    }

    override fun setMeteringPoint(x: Float, y: Float) {
        val cam = camera ?: return
        if (focusLocked) return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    override fun setFocusLocked(locked: Boolean) {
        val cam = camera ?: return
        focusLocked = locked
        if (!locked) cam.cameraControl.cancelFocusAndMetering()
        // When locked, we simply stop issuing new metering actions; CameraX keeps the
        // last converged focus until the next action or cancel.
    }

    override fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    /** Copies the Y plane into a reused buffer (handling row and pixel stride) and hands it to the analyzer. */
    private fun deliver(proxy: ImageProxy, analyzer: FrameAnalyzer) {
        val plane = proxy.planes[0]
        val width = proxy.width
        val height = proxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        buffer.rewind()

        val luma: LumaImage = if (pixelStride == 1) {
            val needed = rowStride * height
            if (lumaBuffer.size < needed) lumaBuffer = ByteArray(needed)
            val available = minOf(buffer.remaining(), needed)
            buffer.get(lumaBuffer, 0, available)
            LumaImage(lumaBuffer, width, height, rowStride)
        } else {
            // Rare: interleaved Y plane. Compact it.
            val needed = width * height
            if (lumaBuffer.size < needed) lumaBuffer = ByteArray(needed)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                for (x in 0 until width) lumaBuffer[y * width + x] = buffer.get(rowStart + x * pixelStride)
            }
            LumaImage(lumaBuffer, width, height)
        }
        analyzer.analyze(Frame(luma, proxy.imageInfo.rotationDegrees, proxy.imageInfo.timestamp))
    }
}
