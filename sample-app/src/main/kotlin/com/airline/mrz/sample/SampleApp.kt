package com.airline.mrz.sample

import android.app.Application
import com.airline.mrz.EngineKind
import com.airline.mrz.PassportScanner
import com.airline.mrz.ScanConfig
import com.airline.mrz.ui.MrzScannerHost

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Models are loaded once here. Until the TFLite models are trained and placed in
        // mrz-engine-tflite/src/main/assets, the ML Kit development engine is used.
        val config = ScanConfig(engine = EngineKind.MLKIT_DEV, allowUnverifiedResult = true)
        MrzScannerHost.install(PassportScanner.create(this, config))
    }
}
