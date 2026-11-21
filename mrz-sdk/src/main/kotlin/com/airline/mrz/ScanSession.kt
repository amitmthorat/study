package com.airline.mrz

import android.os.Build
import com.airline.mrz.camera.FocusController
import com.airline.mrz.camera.FrameSource
import com.airline.mrz.core.integrate.FrameIntegrator
import com.airline.mrz.core.integrate.ReadSession
import com.airline.mrz.core.integrate.StoppingRule
import com.airline.mrz.core.result.FailureReason
import com.airline.mrz.core.result.MrzReadOutcome
import com.airline.mrz.diagnostics.DiagnosticBundle
import com.airline.mrz.engine.Frame
import com.airline.mrz.engine.FramePipeline
import com.airline.mrz.engine.Hint
import com.airline.mrz.engine.MrzEngine
import com.airline.mrz.engine.QualityGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One scanning session: wires a [FrameSource] to the [FramePipeline], publishes
 * [ScanState] for any UI, and completes with a [ScanResult].
 *
 * Frames are processed on the frame source's analysis thread. State is published
 * through a conflated [StateFlow] so a slow UI never backpressures the pipeline.
 * Cancelling the session stops the camera, closes nothing it did not create, and
 * zeroes buffers it owns.
 */
public class ScanSession internal constructor(
    private val engine: MrzEngine,
    private val frameSource: FrameSource,
    private val config: ScanConfig,
    private val sdkVersion: String,
    private val acceleratorName: String,
) {
    private val pipeline = FramePipeline(
        engine = engine,
        gate = QualityGate(),
        readSession = ReadSession(
            FrameIntegrator(),
            StoppingRule(
                StoppingRule.Config(
                    stableFrames = config.stableFrames,
                    minMargin = config.minMargin,
                    timeoutMs = config.timeoutMs,
                    allowUnverifiedResult = config.allowUnverifiedResult,
                ),
            ),
        ),
        config = FramePipeline.Config(vizCrossCheck = config.vizCrossCheck),
    )
    private val focus = FocusController(frameSource)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    private val result = CompletableDeferred<ScanResult>()
    private val started = AtomicBoolean(false)
    private var ticker: Job? = null
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var torchOn = false
    private var lowContrastFrames = 0

    public val state: StateFlow<ScanState> get() = _state

    public fun start() {
        if (!started.compareAndSet(false, true)) return
        _state.value = ScanState.Searching
        frameSource.start { frame -> onFrame(frame) }
        // Timeouts must fire even when the camera delivers nothing (lens covered).
        ticker = scope.launch {
            while (!result.isCompleted) {
                delay(250)
                pipeline.tick()?.let { if (it is FramePipeline.Event.Done) finish(it.outcome) }
            }
        }
    }

    /** Suspends until the session ends. */
    public suspend fun await(): ScanResult = result.await()

    public fun cancel() {
        if (result.complete(ScanResult.Cancelled)) {
            _state.value = ScanState.Done(ScanResult.Cancelled)
        }
        tearDown()
    }

    private fun onFrame(frame: Frame) {
        if (result.isCompleted) return
        val upright = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        analysisWidth = if (upright) frame.luma.height else frame.luma.width
        analysisHeight = if (upright) frame.luma.width else frame.luma.height
        val event = try {
            pipeline.process(frame)
        } catch (e: Exception) {
            finish(MrzReadOutcome.Failed(FailureReason.ENGINE_ERROR, e.javaClass.simpleName))
            return
        }
        when (event) {
            is FramePipeline.Event.NoMrz -> {
                focus.onBand(null, analysisWidth, analysisHeight)
                _state.value = ScanState.Searching
            }
            is FramePipeline.Event.Rejected -> {
                focus.onBand(event.band, analysisWidth, analysisHeight)
                handleTorch(event.hint)
                _state.value = ScanState.Aligning(event.hint, event.band, analysisWidth, analysisHeight)
            }
            is FramePipeline.Event.Dropped -> {
                focus.onBand(event.band, analysisWidth, analysisHeight)
                _state.value = ScanState.Aligning(Hint.HOLD_STILL, event.band, analysisWidth, analysisHeight)
            }
            is FramePipeline.Event.Voted -> {
                focus.onBand(event.band, analysisWidth, analysisHeight)
                val p = event.progress
                _state.value = ScanState.Reading(
                    band = event.band,
                    frameWidth = analysisWidth,
                    frameHeight = analysisHeight,
                    progress = (p.stableFrames.toFloat() / config.stableFrames).coerceIn(0f, 0.95f),
                    checksPassing = p.checksPassing,
                    framesVoted = p.framesVoted,
                )
            }
            is FramePipeline.Event.Done -> finish(event.outcome)
        }
    }

    private fun handleTorch(hint: Hint) {
        if (config.torch != TorchMode.AUTO || !frameSource.torchAvailable) return
        if (hint == Hint.MORE_LIGHT) {
            if (++lowContrastFrames >= 5 && !torchOn) {
                torchOn = true; frameSource.setTorch(true)
            }
        } else {
            lowContrastFrames = 0
        }
    }

    private fun finish(outcome: MrzReadOutcome) {
        val diagnostics = diagnostics()
        val scanResult: ScanResult = when (outcome) {
            is MrzReadOutcome.Verified -> ScanResult.Success(outcome.passport, outcome.read, diagnostics)
            is MrzReadOutcome.Unverified -> ScanResult.Unverified(outcome.passport, outcome.read, outcome.reason, diagnostics)
            is MrzReadOutcome.Failed -> ScanResult.Failed(outcome.reason, outcome.detail, diagnostics)
        }
        if (result.complete(scanResult)) {
            _state.value = ScanState.Done(scanResult)
            tearDown()
        }
    }

    private fun tearDown() {
        ticker?.cancel()
        try {
            frameSource.setTorch(false)
            frameSource.stop()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private fun diagnostics(): DiagnosticBundle {
        val t = pipeline.timings
        fun mean(total: Long, n: Int) = if (n == 0) 0f else total / 1_000_000f / n
        val stages = if (config.diagnosticsLevel == DiagnosticsLevel.TIMINGS) mapOf(
            "detect" to mean(t.detectNanos, t.framesSeen),
            "rectify" to mean(t.rectifyNanos, t.framesSeen),
            "quality" to mean(t.qualityNanos, t.framesSeen),
            "recognize" to mean(t.recognizeNanos, t.framesVoted + t.framesRejected),
            "viz" to mean(t.vizNanos, maxOf(1, t.framesVoted / 4)),
        ) else emptyMap()
        return DiagnosticBundle(
            sdkVersion = sdkVersion,
            modelVersion = engine.modelVersion,
            deviceModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            accelerator = acceleratorName,
            analysisWidth = analysisWidth,
            analysisHeight = analysisHeight,
            framesSeen = t.framesSeen,
            framesVoted = t.framesVoted,
            framesRejected = t.framesRejected,
            hintCounts = if (config.diagnosticsLevel == DiagnosticsLevel.NONE) emptyMap() else t.hintCounts.mapKeys { it.key.name },
            durationMs = pipeline.elapsedMs(),
            stageMeansMs = stages,
        )
    }
}
