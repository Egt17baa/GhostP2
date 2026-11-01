package org.ghostp2.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.ghostp2.app.BatteryOptimization
import org.ghostp2.app.ui.ActivityActions

/** Pasos de la bienvenida, en este orden. */
enum class OnboardingStep { WELCOME, NOTIFICATIONS, BATTERY }

/**
 * Pasos que hacen falta: [OnboardingStep.WELCOME] siempre; [OnboardingStep.NOTIFICATIONS] solo en API 33+
 * sin el permiso (antes no hay permiso que pedir); [OnboardingStep.BATTERY] solo sin la exención.
 */
fun onboardingSteps(sdkInt: Int, notificationsGranted: Boolean, batteryIgnoring: Boolean): List<OnboardingStep> =
    buildList {
        add(OnboardingStep.WELCOME)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) add(OnboardingStep.NOTIFICATIONS)
        if (!batteryIgnoring) add(OnboardingStep.BATTERY)
    }

/**
 * Bienvenida con el estado real del móvil. Los pasos se fijan al entrar (conceder un permiso no hace
 * saltar de paso); la exención de batería se vuelve a leer en cada `onResume`, al volver del diálogo del
 * sistema, porque la actividad no la guarda.
 */
@Composable
fun OnboardingRoute(actions: ActivityActions, onFinish: () -> Unit) {
    val context = LocalContext.current
    var batteryIgnoring by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    val steps = remember {
        onboardingSteps(Build.VERSION.SDK_INT, notificationsGranted(context), batteryIgnoring)
    }
    LifecycleResumeEffect(context) {
        batteryIgnoring = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }
    OnboardingScreen(
        steps = steps,
        batteryIgnoring = batteryIgnoring,
        onRequestNotifications = actions.requestNotifications,
        onRequestBattery = actions.requestBatteryExemption,
        onFinish = onFinish,
    )
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
