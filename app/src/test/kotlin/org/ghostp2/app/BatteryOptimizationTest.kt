package org.ghostp2.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class BatteryOptimizationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun requestOpensTheDirectDialog() {
        val launched = mutableListOf<Intent>()
        BatteryOptimization.launchRequest(context) { launched += it }
        assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), launched.map { it.action })
        assertEquals("package:${context.packageName}", launched.single().dataString)
    }

    @Test
    fun missingDialogFallsBackToTheSystemList() {
        val launched = mutableListOf<String?>()
        BatteryOptimization.launchRequest(context) {
            launched += it.action
            if (it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) throw ActivityNotFoundException()
        }
        assertEquals(
            listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            launched,
        )
    }

    @Test
    fun neitherScreenDoesNotCrash() {
        var attempts = 0
        BatteryOptimization.launchRequest(context) {
            attempts++
            throw ActivityNotFoundException()
        }
        assertEquals(2, attempts)
    }
}
