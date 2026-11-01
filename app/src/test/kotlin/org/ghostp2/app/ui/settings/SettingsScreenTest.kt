package org.ghostp2.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.Notice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<String>()

    private var settings by mutableStateOf<AppSettings?>(AppSettings(onboardingDone = true))
    private var wiping by mutableStateOf(false)
    private var lockNeedsDeviceLock by mutableStateOf(false)

    private fun show() = compose.setContent {
        SettingsScreen(
            settings = settings,
            batteryIgnoring = false,
            manufacturerHint = R.string.battery_hint_generic,
            version = "0.1.0",
            myOnion = ONION,
            lastDiagnostic = Notice.PublicationFailed,
            lockNeedsDeviceLock = lockNeedsDeviceLock,
            saveFailed = false,
            wiping = wiping,
            wipeFailed = false,
            onBack = { events += "back" },
            onSetLock = { events += "lock:$it" },
            onSetLockAfter = { events += "after:$it" },
            onSetHideNames = { events += "hide:$it" },
            onSetMaxPhotoSide = { events += "side:$it" },
            onRequestBattery = { events += "battery" },
            onSetStartOnBoot = { events += "boot:$it" },
            onSetDisconnectAfter = {
                events += "disconnect:$it"
                // Como el ViewModel: el ajuste guardado vuelve a la pantalla.
                settings = settings?.copy(disconnectAfterMinutes = it)
            },
            onWipeAll = { events += "wipe" },
        )
    }

    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun wipeAllAsksForConfirmation() {
        show()
        compose.onNodeWithText(text(R.string.settings_wipe)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_wipe_confirm_text)).assertExists()
        assertEquals(emptyList<String>(), events)

        // Cancelar en la primera confirmación: nada.
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onAllNodesWithText(text(R.string.settings_wipe_confirm_text)).assertCountEquals(0)
        assertEquals(emptyList<String>(), events)

        // Primera confirmación ⇒ segunda; cancelar en la segunda: nada.
        compose.onNodeWithText(text(R.string.settings_wipe)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_wipe_confirm)).performClick()
        compose.onNodeWithText(text(R.string.settings_wipe_confirm_again_text)).assertExists()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onAllNodesWithText(text(R.string.settings_wipe_confirm_again_text)).assertCountEquals(0)
        assertEquals(emptyList<String>(), events)

        // Las dos confirmaciones ⇒ se borra.
        compose.onNodeWithText(text(R.string.settings_wipe)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_wipe_confirm)).performClick()
        compose.onNodeWithText(text(R.string.settings_wipe_confirm_again)).performClick()
        assertEquals(listOf("wipe"), events)
        compose.onAllNodesWithText(text(R.string.settings_wipe_confirm_again_text)).assertCountEquals(0)
    }

    @Test
    fun showsEverySectionAndForwardsChanges() {
        show()
        compose.onNodeWithText(text(R.string.settings_lock)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_lock_after_5)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_hide_names)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_photo_side, 3072)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_battery_allow)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_start_on_boot)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.battery_hint_generic)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_license)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_version, "0.1.0")).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_my_onion, abbreviateOnion(ONION))).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_diagnostic_publication)).performScrollTo()

        assertEquals(listOf("lock:true", "after:5", "hide:true", "side:3072", "battery", "boot:true"), events)
    }

    @Test
    fun disconnectOptionPersists() {
        show()
        compose.onNodeWithText(text(R.string.settings_disconnect_after)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_disconnect_after_detail)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_disconnect_after_0)).performScrollTo().assertIsSelected()
        for (id in listOf(R.string.settings_disconnect_after_2, R.string.settings_disconnect_after_5, R.string.settings_disconnect_after_15)) {
            compose.onNodeWithText(text(id)).performScrollTo().assertIsNotSelected()
        }

        compose.onNodeWithText(text(R.string.settings_disconnect_after_5)).performScrollTo().performClick()
        assertEquals(listOf("disconnect:5"), events)
        assertEquals(5, settings?.disconnectAfterMinutes)
        compose.onNodeWithText(text(R.string.settings_disconnect_after_5)).assertIsSelected()
        compose.onNodeWithText(text(R.string.settings_disconnect_after_0)).assertIsNotSelected()
    }

    @Test
    fun lockWithoutDeviceLockShowsTheReason() {
        lockNeedsDeviceLock = true
        show()
        compose.onNodeWithText(text(R.string.settings_lock_needs_device_lock)).performScrollTo()
    }

    @Test
    fun wipingDisablesTheButton() {
        wiping = true
        show()
        compose.onNodeWithText(text(R.string.settings_wiping)).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_wipe)).performScrollTo().performClick()
        compose.onAllNodesWithText(text(R.string.settings_wipe_confirm_text)).assertCountEquals(0)
    }

    @Test
    fun onionIsAbbreviated() {
        val short = abbreviateOnion(ONION)
        assertEquals("abcdefgh…uvwxyz.onion", short)
        assertEquals("corta.onion", abbreviateOnion("corta.onion"))
    }

    private companion object {
        const val ONION = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzuvwxyz.onion"
    }
}
