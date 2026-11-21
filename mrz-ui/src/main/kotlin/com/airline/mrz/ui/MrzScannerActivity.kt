package com.airline.mrz.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.airline.mrz.PassportScanner
import com.airline.mrz.ScanConfig
import com.airline.mrz.ScanResult
import com.airline.mrz.contract.ScanPassportContract

/**
 * Hosts [MrzScannerScreen] for the Activity-result integration tier. The
 * [PassportScanner] instance is obtained from [MrzScannerHost], which the host app
 * installs once at startup so models are not reloaded per scan.
 */
public class MrzScannerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val config = intent.getSerializableExtra(ScanPassportContract.EXTRA_CONFIG) as? ScanConfig ?: ScanConfig()
        if (config.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val scanner = MrzScannerHost.scanner ?: run {
            finishWith(ScanResult.Failed(com.airline.mrz.core.result.FailureReason.ENGINE_ERROR, "MrzScannerHost.install was not called", emptyDiagnostics()))
            return
        }
        setContent {
            MrzScannerScreen(scanner = scanner, config = config, onResult = ::finishWith)
        }
    }

    private fun finishWith(result: ScanResult) {
        val data = Intent().putExtra(ScanPassportContract.EXTRA_RESULT, result)
        setResult(if (result is ScanResult.Cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK, data)
        finish()
    }

    private fun emptyDiagnostics() = com.airline.mrz.diagnostics.DiagnosticBundle(
        sdkVersion = "", modelVersion = "", deviceModel = android.os.Build.MODEL, androidSdk = android.os.Build.VERSION.SDK_INT,
        accelerator = "", analysisWidth = 0, analysisHeight = 0, framesSeen = 0, framesVoted = 0, framesRejected = 0,
        hintCounts = emptyMap(), durationMs = 0, stageMeansMs = emptyMap(),
    )
}

/** Process-wide holder for the scanner used by [MrzScannerActivity]. */
public object MrzScannerHost {
    @Volatile
    public var scanner: PassportScanner? = null
        private set

    /** Call once, for example from `Application.onCreate`, after creating the scanner. */
    public fun install(scanner: PassportScanner) {
        this.scanner = scanner
    }

    public fun uninstall() {
        scanner?.close()
        scanner = null
    }
}
