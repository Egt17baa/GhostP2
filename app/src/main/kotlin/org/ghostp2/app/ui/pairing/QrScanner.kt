package org.ghostp2.app.ui.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostp2.app.R
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Escáner del QR de una invitación. Pide el permiso `CAMERA` al mostrarse (solo aquí: la cámara solo se
 * usa para esto, R23). Sin permiso: "Sin permiso de cámara. Puedes pegar la invitación" y "Pegar"
 * ([onUsePaste]); el permiso se vuelve a leer en cada `onResume` (por si se concede en los ajustes).
 *
 * Con permiso, la vista previa de la cámara trasera y un [QrAnalyzer] fuera del hilo principal. Solo
 * se avisa a [onInvitation] (en el hilo principal) con una invitación de GhostP2; los QR ajenos se
 * ignoran. Los fotogramas no se guardan ni se registran, y la cámara se desvincula al salir.
 */
@Composable
fun QrScanner(onInvitation: (String) -> Unit, onUsePaste: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    LifecycleResumeEffect(context) {
        granted = hasCameraPermission(context)
        onPauseOrDispose { }
    }
    if (granted) {
        CameraPreview(onInvitation, onUsePaste)
    } else {
        UsePaste(stringResource(R.string.scan_no_permission), onUsePaste)
    }
}

@Composable
private fun CameraPreview(onInvitation: (String) -> Unit, onUsePaste: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnInvitation by rememberUpdatedState(onInvitation)
    var failed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        // Solo se toca en el hilo principal: tras salir no se vincula ni se acepta nada.
        var disposed = false
        // Un hilo propio para el análisis: ZXing nunca corre en el hilo principal.
        val analysisThread = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisThread, QrAnalyzer { text -> main.execute { if (!disposed) currentOnInvitation(text) } }) }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get()
                provider = cameras
                cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: ExecutionException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                // Sin cámara trasera.
                failed = true
            } catch (_: IllegalStateException) {
                failed = true
            }
        }, main)
        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analysisThread.shutdown()
        }
    }

    if (failed) {
        UsePaste(stringResource(R.string.scan_camera_failed), onUsePaste)
        return
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO))
        Text(stringResource(R.string.scan_hint), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onUsePaste) { Text(stringResource(R.string.add_tab_paste)) }
    }
}

@Composable
private fun UsePaste(message: String, onUsePaste: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(message, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onUsePaste) { Text(stringResource(R.string.add_tab_paste)) }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private const val PREVIEW_RATIO = 3f / 4f
