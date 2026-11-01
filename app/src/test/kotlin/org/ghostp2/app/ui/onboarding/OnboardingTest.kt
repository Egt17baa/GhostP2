package org.ghostp2.app.ui.onboarding

import org.ghostp2.app.BatteryOptimization
import org.ghostp2.app.R
import org.ghostp2.app.ui.onboarding.OnboardingStep.BATTERY
import org.ghostp2.app.ui.onboarding.OnboardingStep.NOTIFICATIONS
import org.ghostp2.app.ui.onboarding.OnboardingStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingTest {
    @Test
    fun notificationsStepOnlyOnApi33WithoutPermission() {
        assertEquals(listOf(WELCOME, NOTIFICATIONS, BATTERY), onboardingSteps(33, notificationsGranted = false, batteryIgnoring = false))
        assertEquals(listOf(WELCOME, NOTIFICATIONS, BATTERY), onboardingSteps(36, notificationsGranted = false, batteryIgnoring = false))
        // Con el permiso ya concedido, o antes de API 33 (no hay permiso que pedir): sin ese paso.
        assertEquals(listOf(WELCOME, BATTERY), onboardingSteps(33, notificationsGranted = true, batteryIgnoring = false))
        assertEquals(listOf(WELCOME, BATTERY), onboardingSteps(32, notificationsGranted = false, batteryIgnoring = false))
        assertEquals(listOf(WELCOME, BATTERY), onboardingSteps(26, notificationsGranted = false, batteryIgnoring = false))
    }

    @Test
    fun batteryStepSkippedWhenExempt() {
        assertEquals(listOf(WELCOME, NOTIFICATIONS), onboardingSteps(35, notificationsGranted = false, batteryIgnoring = true))
        assertEquals(listOf(WELCOME), onboardingSteps(35, notificationsGranted = true, batteryIgnoring = true))
        assertEquals(listOf(WELCOME), onboardingSteps(30, notificationsGranted = false, batteryIgnoring = true))
    }

    @Test
    fun manufacturerHints() {
        assertEquals(R.string.battery_hint_xiaomi, BatteryOptimization.manufacturerHint("Xiaomi"))
        assertEquals(R.string.battery_hint_xiaomi, BatteryOptimization.manufacturerHint("Redmi"))
        assertEquals(R.string.battery_hint_xiaomi, BatteryOptimization.manufacturerHint("POCO"))
        assertEquals(R.string.battery_hint_samsung, BatteryOptimization.manufacturerHint("samsung"))
        assertEquals(R.string.battery_hint_huawei, BatteryOptimization.manufacturerHint("HUAWEI"))
        assertEquals(R.string.battery_hint_huawei, BatteryOptimization.manufacturerHint("HONOR"))
        assertEquals(R.string.battery_hint_oppo, BatteryOptimization.manufacturerHint("OnePlus"))
        assertEquals(R.string.battery_hint_oppo, BatteryOptimization.manufacturerHint("OPPO"))
        assertEquals(R.string.battery_hint_oppo, BatteryOptimization.manufacturerHint("realme"))
        assertEquals(R.string.battery_hint_generic, BatteryOptimization.manufacturerHint("Google"))
        assertEquals(R.string.battery_hint_generic, BatteryOptimization.manufacturerHint(""))
    }

    @Test
    fun notificationAccessIsBlockedOnlyAfterAskingWithoutRationale() {
        assertEquals(NotificationAccess.GRANTED, notificationAccess(granted = true, asked = true, showRationale = false))
        // Sin pedirlo todavía, la falta de justificación no significa "denegado para siempre".
        assertEquals(NotificationAccess.ASK, notificationAccess(granted = false, asked = false, showRationale = false))
        // Primera denegación: el sistema todavía puede preguntar otra vez.
        assertEquals(NotificationAccess.ASK, notificationAccess(granted = false, asked = true, showRationale = true))
        assertEquals(NotificationAccess.BLOCKED, notificationAccess(granted = false, asked = true, showRationale = false))
    }
}
