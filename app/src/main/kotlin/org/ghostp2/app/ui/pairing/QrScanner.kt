package org.ghostp2.app.ui.pairing

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostp2.app.R
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Qué muestra el escáner según la cámara y su permiso. */
internal enum class CameraAccess {
    /** El móvil no tiene ninguna cámara: no se pide el permiso. */
    NO_CAMERA,

    /** Sin permiso (aún sin respuesta o denegado una vez): se pide al mostrarse. */
    DENIED,

    /** Denegado y el sistema ya no preguntará: solo queda abrir los ajustes de la app. */
    BLOCKED,
    GRANTED,
}

/**
 * Sin cámara no se pide nada; concedido gana; denegado tras pedirlo y sin justificación que mostrar
 * es que el sistema no volverá a preguntar.
 */
internal fun cameraAccess(hasCamera: Boolean, granted: Boolean, asked: Boolean, showRationale: Boolean): CameraAccess =
    when {
        !hasCamera -> CameraAccess.NO_CAMERA
        granted -> CameraAccess.GRANTED
        asked && !showRationale -> CameraAccess.BLOCKED
        else -> CameraAccess.DENIED
    }

/**
 * Textos del escáner según lo que se lee: sin cámara, sin permiso, permiso bloqueado, cámara que no abre y
 * la indicación bajo la vista previa.
 */
internal class ScanTexts(
    @StringRes val noCamera: Int,
    @StringRes val noPermission: Int,
    @StringRes val blocked: Int,
    @StringRes val cameraFailed: Int,
    @StringRes val hint: Int,
)

/** Los de la invitación ("Puedes pegar la invitación"). */
internal val INVITATION_SCAN_TEXTS = ScanTexts(
    noCamera = R.string.scan_no_camera,
    noPermission = R.string.scan_no_permission,
    blocked = R.string.scan_blocked,
    cameraFailed = R.string.scan_camera_failed,
    hint = R.string.scan_hint,
)

/**
 * Escáner del QR de una invitación. Con cámara (`FEATURE_CAMERA_ANY`), pide el permiso `CAMERA` al
 * mostrarse (solo aquí: la cámara solo se usa para esto, R23); sin cámara no pide nada y ofrece pegar.
 * Sin permiso: "Sin permiso de cámara. Puedes pegar la invitación" y "Pegar" ([onUsePaste]); denegado
 * para siempre, además "Abrir ajustes" (los de la app). El permiso se vuelve a leer en cada `onResume`.
 *
 * Con permiso, la vista previa de la cámara trasera y un [QrAnalyzer] fuera del hilo principal. Solo
 * se avisa a [onInvitation] (en el hilo principal) con una invitación de GhostP2; los QR ajenos se
 * ignoran. Los fotogramas no se guardan ni se registran, y la cámara se desvincula al salir.
 */
@Composable
fun QrScanner(onInvitation: (String) -> Unit, onUsePaste: () -> Unit) {
    QrCodeScanner(accept = ::invitationOrNull, onResult = onInvitation, onUsePaste = onUsePaste, texts = INVITATION_SCAN_TEXTS)
}

/**
 * Escáner de QR general, igual que [QrScanner]: solo avisa a [onResult] (una vez, en el hilo principal) con lo que
 * devuelva [accept] para el texto de un QR (que corre en el hilo del analizador); con `null`, el QR se ignora.
 * También lo usa «Mis puentes» (R30) para los QR de bridges.torproject.org.
 */
@Composable
internal fun QrCodeScanner(accept: (String) -> String?, onResult: (String) -> Unit, onUsePaste: () -> Unit, texts: ScanTexts) {
    QrScannerContent(onUsePaste, texts) { CameraPreview(accept, onResult, onUsePaste, texts) }
}

/** [QrCodeScanner] con la vista de la cámara aparte ([camera]), para probar el permiso sin CameraX. */
@Composable
internal fun QrScannerContent(onUsePaste: () -> Unit, texts: ScanTexts = INVITATION_SCAN_TEXTS, camera: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var asked by remember { mutableStateOf(false) }
    fun read() = cameraAccess(
        hasCamera = hasCamera,
        granted = hasCameraPermission(context),
        asked = asked,
        showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false,
    )
    var access by remember { mutableStateOf(read()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        access = read()
    }
    LaunchedEffect(Unit) { if (access == CameraAccess.DENIED) request.launch(Manifest.permission.CAMERA) }
    LifecycleResumeEffect(context) {
        access = read()
        onPauseOrDispose { }
    }
    when (access) {
        CameraAccess.GRANTED -> camera()
        CameraAccess.NO_CAMERA -> UsePaste(stringResource(texts.noCamera), onUsePaste)
        CameraAccess.DENIED -> UsePaste(stringResource(texts.noPermission), onUsePaste)
        CameraAccess.BLOCKED -> UsePaste(stringResource(texts.blocked), onUsePaste) {
            Button(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.scan_open_settings)) }
        }
    }
}

/** Ajustes de GhostP2 en el sistema, donde se concede la cámara. */
internal fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(appSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        // Sin pantalla de ajustes de la app: queda pegar el texto.
    }
}

@Composable
private fun CameraPreview(accept: (String) -> String?, onResult: (String) -> Unit, onUsePaste: () -> Unit, texts: ScanTexts) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
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
            .also { it.setAnalyzer(analysisThread, QrAnalyzer(accept) { text -> main.execute { if (!disposed) currentOnResult(text) } }) }
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
        UsePaste(stringResource(texts.cameraFailed), onUsePaste)
        return
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_RATIO))
        Text(stringResource(texts.hint), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onUsePaste) { Text(stringResource(R.string.add_tab_paste)) }
    }
}

@Composable
private fun UsePaste(message: String, onUsePaste: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(message, textAlign = TextAlign.Center)
        extra()
        OutlinedButton(onClick = onUsePaste) { Text(stringResource(R.string.add_tab_paste)) }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private const val PREVIEW_RATIO = 3f / 4f
