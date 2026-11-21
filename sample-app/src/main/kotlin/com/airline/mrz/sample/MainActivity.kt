package com.airline.mrz.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airline.mrz.EngineKind
import com.airline.mrz.ScanConfig
import com.airline.mrz.ScanResult
import com.airline.mrz.contract.ScanPassportContract

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var last by remember { mutableStateOf("No scan yet") }
            val launch = rememberLauncherForActivityResult(ScanPassportContract()) { result -> last = describe(result) }
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Button(onClick = { launch.launch(ScanConfig(engine = EngineKind.MLKIT_DEV, allowUnverifiedResult = true)) }) {
                    Text("Scan passport")
                }
                Text(last, Modifier.padding(top = 24.dp))
            }
        }
    }

    private fun describe(r: ScanResult): String = when (r) {
        is ScanResult.Success -> buildString {
            val p = r.passport
            appendLine("VERIFIED in ${r.read.durationMs} ms over ${r.read.framesVoted} frames")
            appendLine("${p.primaryIdentifier}, ${p.secondaryIdentifier}")
            appendLine("Doc ${p.documentNumber}  ${p.issuingStateCanonical}/${p.nationalityCanonical}  ${p.sex}")
            appendLine("DOB ${p.dateOfBirth}  Expiry ${p.dateOfExpiry}")
            appendLine("Checks ${r.read.checks}")
            if (r.read.corrections.isNotEmpty()) appendLine("Corrections ${r.read.corrections}")
            appendLine("Device ${r.diagnostics.deviceModel} ${r.diagnostics.analysisWidth}x${r.diagnostics.analysisHeight} ${r.diagnostics.accelerator}")
        }
        is ScanResult.Unverified -> "UNVERIFIED (${r.reason}): ${r.read?.correctedLine1}\n${r.read?.correctedLine2}\nchecks ${r.read?.checks}"
        is ScanResult.Failed -> "FAILED ${r.reason} ${r.detail ?: ""}\nhints ${r.diagnostics.hintCounts}"
        ScanResult.Cancelled -> "Cancelled"
    }
}
