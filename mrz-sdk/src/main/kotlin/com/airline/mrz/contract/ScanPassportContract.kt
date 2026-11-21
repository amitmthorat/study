package com.airline.mrz.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.airline.mrz.ScanConfig
import com.airline.mrz.ScanResult

/**
 * Drop-in integration: launches the SDK's scanning screen and returns a [ScanResult].
 *
 * ```
 * val launch = registerForActivityResult(ScanPassportContract()) { result -> ... }
 * launch.launch(ScanConfig())
 * ```
 *
 * The scanner Activity lives in the `mrz-ui` module and is resolved by action so this
 * module does not depend on the UI.
 */
public class ScanPassportContract : ActivityResultContract<ScanConfig, ScanResult>() {

    override fun createIntent(context: Context, input: ScanConfig): Intent =
        Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_CONFIG, input)

    override fun parseResult(resultCode: Int, intent: Intent?): ScanResult {
        @Suppress("DEPRECATION")
        val r = intent?.getSerializableExtra(EXTRA_RESULT) as? ScanResult
        return r ?: ScanResult.Cancelled
    }

    public companion object {
        public const val ACTION: String = "com.airline.mrz.action.SCAN_PASSPORT"
        public const val EXTRA_CONFIG: String = "com.airline.mrz.extra.CONFIG"
        public const val EXTRA_RESULT: String = "com.airline.mrz.extra.RESULT"
    }
}
