package com.airline.mrz.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airline.mrz.PassportScanner
import com.airline.mrz.ScanConfig
import com.airline.mrz.ScanResult
import com.airline.mrz.ScanSession
import com.airline.mrz.ScanState
import com.airline.mrz.ui.overlay.HintBanner
import com.airline.mrz.ui.overlay.ViewfinderOverlay
import com.airline.mrz.ui.theme.MrzTheme

/**
 * The SDK's scanning screen: camera preview, passport guide, band highlight, hints and
 * a cancel button. Requests the camera permission itself. Calls [onResult] exactly
 * once and stops the camera.
 *
 * [scanner] is created once per process by the host (see [PassportScanner.create]).
 */
@Composable
public fun MrzScannerScreen(
    scanner: PassportScanner,
    config: ScanConfig,
    onResult: (ScanResult) -> Unit,
    modifier: Modifier = Modifier,
    theme: MrzTheme = MrzTheme.Default,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }

    if (!permitted) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(if (denied) R.string.mrz_camera_permission_denied else R.string.mrz_camera_permission_rationale), color = theme.hintText)
        }
        return
    }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var session by remember { mutableStateOf<ScanSession?>(null) }

    DisposableEffect(scanner, config) {
        val s = scanner.startCameraSession(lifecycleOwner, previewView, config)
        session = s
        onDispose { s.cancel() }
    }

    val state by (session?.state ?: return).collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        val done = state as? ScanState.Done ?: return@LaunchedEffect
        onResult(done.result)
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ViewfinderOverlay(state, theme, Modifier.fillMaxSize())
        HintBanner(state, theme, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        TextButton(onClick = { session?.cancel() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.mrz_cancel), color = theme.hintText)
        }
    }
}
