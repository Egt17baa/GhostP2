package org.ghostp2.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.ui.pairing.QrScannerContent
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.BuiltInBridges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class BridgesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<String>()
    private var settings by mutableStateOf<AppSettings?>(AppSettings(onboardingDone = true))

    /** Lo que responde el ViewModel a "Guardar". */
    private var answer = BridgeChange.SAVED

    private fun show() = compose.setContent {
        BridgesScreen(
            settings = settings,
            saveFailed = false,
            onBack = { events += "back" },
            onSave = { mode, text ->
                events += "save:$mode:${text?.lines()?.size}"
                saved = text
                answer
            },
            onConfirmReconnect = { events += "reconnect" },
            onCancelReconnect = { events += "cancel" },
            onDone = { events += "done" },
            // El escáner de las pruebas de emparejamiento, con una "cámara" que lee QR.
            scanner = { onScanned, onClose ->
                QrScannerContent(onUsePaste = onClose, texts = BRIDGE_SCAN_TEXTS) {
                    Button(onClick = { onScanned(QR) }) { Text("leer QR") }
                }
            },
        )
    }

    private var saved: String? = null

    private fun field() = compose.onNode(hasSetTextAction())

    @Test
    fun showsFourOptionsWithNoneSelected() {
        show()
        compose.onNodeWithText(HELP).assertExists()
        compose.onNodeWithText("No").assertIsSelected()
        for (option in listOf("obfs4", "Snowflake", "Mis puentes")) compose.onNodeWithText(option).assertIsNotSelected()
        // No hay WebTunnel integrado (solo en "Mis puentes") ni campo de texto sin elegir "Mis puentes".
        compose.onAllNodesWithText("WebTunnel").assertCountEquals(0)
        compose.onAllNodesWithText("Escanear QR").assertCountEquals(0)

        compose.onNodeWithText("obfs4").performClick()
        compose.onNodeWithText("obfs4").assertIsSelected()
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        assertEquals(listOf("save:OBFS4:null", "done"), events)
    }

    @Test
    fun snowflakeShowsWarningAndCancelKeepsNone() {
        show()
        compose.onNodeWithText("Snowflake").performClick()
        compose.onNodeWithText(SNOWFLAKE_WARNING).assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onAllNodesWithText(SNOWFLAKE_WARNING).assertCountEquals(0)
        compose.onNodeWithText("No").assertIsSelected()
        compose.onNodeWithText("Snowflake").assertIsNotSelected()
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithText("Snowflake").performClick()
        compose.onNodeWithText("Usar Snowflake").performClick()
        compose.onNodeWithText("Snowflake").assertIsSelected()
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        assertEquals(listOf("save:SNOWFLAKE:null", "done"), events)
    }

    @Test
    fun customInvalidShowsLineErrorsAndSavesNothing() {
        show()
        compose.onNodeWithText("Mis puentes").performClick()

        // Vacío.
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.onNodeWithText("Pega al menos un puente").assertExists()

        // Líneas 1 y 3 no válidas (la 2 es buena).
        field().performTextInput("obfs4 basura\n${BuiltInBridges.OBFS4[0].line}\nmeek_lite 192.0.2.1:80 ${"A".repeat(40)}")
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.onNodeWithText("Línea 1 no válida").assertExists()
        compose.onNodeWithText("Línea 3 no válida").assertExists()
        compose.onAllNodesWithText("Línea 2 no válida").assertCountEquals(0)
        compose.onAllNodesWithText("Pega al menos un puente").assertCountEquals(0)

        // Más de 10.
        val eleven = (1..11).joinToString("\n") { n -> "obfs4 192.0.2.$n:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0" }
        field().performTextReplacement(eleven)
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.onNodeWithText("Máximo 10 puentes").assertExists()
        compose.onAllNodesWithText("Línea 1 no válida").assertCountEquals(0)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun customValidSaves() {
        show()
        compose.onNodeWithText("Mis puentes").performClick()
        field().performTextInput("Bridge ${BuiltInBridges.OBFS4[0].line}\r\n${BuiltInBridges.OBFS4[1].line}")
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        assertEquals(listOf("save:CUSTOM:2", "done"), events)
    }

    @Test
    fun savedCustomLinesAreShown() {
        settings = AppSettings(bridgeMode = BridgeMode.CUSTOM, customBridges = listOf(BuiltInBridges.OBFS4[2].line))
        show()
        compose.onNodeWithText("Mis puentes").assertIsSelected()
        compose.onNodeWithText(BuiltInBridges.OBFS4[2].line).assertExists()
    }

    @Test
    fun scanQrFillsField() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        show()
        compose.onNodeWithText("Mis puentes").performClick()
        compose.onNodeWithText("Escanear QR").performScrollTo().performClick()

        compose.onNodeWithText("leer QR").performClick()

        // De vuelta al campo, con las dos líneas del QR.
        compose.onAllNodesWithText("leer QR").assertCountEquals(0)
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        assertEquals(listOf("save:CUSTOM:2", "done"), events)
        assertEquals(BuiltInBridges.OBFS4.take(2).joinToString("\n") { it.line }, saved)
    }

    @Test
    fun scannerWithoutPermissionTalksAboutBridges() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        show()
        compose.onNodeWithText("Mis puentes").performClick()
        compose.onNodeWithText("Escanear QR").performScrollTo().performClick()
        compose.onNodeWithText("Este móvil no tiene cámara. Puedes pegar los puentes").assertExists()
        compose.onNodeWithText("Pegar").performClick()
        compose.onNodeWithText("Escanear QR").assertExists()
    }

    @Test
    fun bridgeQrAcceptsOnlyBridges() {
        assertEquals(BuiltInBridges.OBFS4.take(2).joinToString("\n") { it.line }, bridgeQrLines(QR))
        assertNull(bridgeQrLines("ghostp2:invitacion"))
        assertNull(bridgeQrLines("[]"))
        assertNull(bridgeQrLines("['snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72']"))
    }

    @Test
    fun changeNeedingReconnectAsksFirst() {
        answer = BridgeChange.NEEDS_RECONNECT
        show()
        compose.onNodeWithText("obfs4").performClick()
        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.onNodeWithText(RECONNECT).assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onAllNodesWithText(RECONNECT).assertCountEquals(0)
        assertEquals(listOf("save:OBFS4:null", "cancel"), events)

        compose.onNodeWithText("Guardar").performScrollTo().performClick()
        compose.onNodeWithText("Reconectar").performClick()
        compose.onAllNodesWithText(RECONNECT).assertCountEquals(0)
        assertEquals(listOf("save:OBFS4:null", "cancel", "save:OBFS4:null", "reconnect", "done"), events)
    }

    private companion object {
        const val HELP = "Los puentes disfrazan tu conexión para que no se note que usas Tor. Lo más discreto son tus " +
            "propios puentes obfs4 o WebTunnel de bridges.torproject.org."
        const val SNOWFLAKE_WARNING = "Snowflake contacta con un intermediario a través de una red de distribución de " +
            "contenidos, con servidores STUN públicos y con voluntarios que hacen de puente. No ven tus mensajes, pero " +
            "sí tu dirección IP. Úsalo solo si los otros no funcionan."
        const val RECONNECT = "Para usar los nuevos puentes hay que reconectar. Se cerrarán las conversaciones abiertas."
        val QR = "['${BuiltInBridges.OBFS4[0].line}', '${BuiltInBridges.OBFS4[1].line}']"
    }
}
