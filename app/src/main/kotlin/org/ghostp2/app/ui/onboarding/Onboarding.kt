package org.ghostp2.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.ghostp2.app.BatteryOptimization
import org.ghostp2.app.ui.ActivityActions

/** Pasos de la bienvenida, en este orden. */
enum class OnboardingStep { WELCOME, NOTIFICATIONS, BATTERY }

/** Estado del permiso de notificaciones: se puede pedir, concedido o denegado para siempre. */
enum class NotificationAccess { ASK, GRANTED, BLOCKED }

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
 * Sin conceder, ya pedido en esta bienvenida y sin justificación que mostrar: el sistema no vuelve a
 * preguntar (denegado para siempre) y solo queda abrir los ajustes de notificaciones de la app.
 */
fun notificationAccess(granted: Boolean, asked: Boolean, showRationale: Boolean): NotificationAccess = when {
    granted -> NotificationAccess.GRANTED
    asked && !showRationale -> NotificationAccess.BLOCKED
    else -> NotificationAccess.ASK
}

/**
 * Bienvenida con el estado real del móvil. Los pasos se fijan al entrar (conceder un permiso no hace
 * saltar de paso); la exención de batería y el permiso de notificaciones se vuelven a leer en cada
 * `onResume` (al volver del diálogo o de los ajustes del sistema), porque la actividad no los guarda.
 * [saveFailed]: no se pudo guardar la bienvenida como hecha.
 */
@Composable
fun OnboardingRoute(actions: ActivityActions, saveFailed: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    var batteryIgnoring by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    var notificationsAsked by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(readNotificationAccess(context, asked = false)) }
    val steps = remember {
        onboardingSteps(Build.VERSION.SDK_INT, notifications == NotificationAccess.GRANTED, batteryIgnoring)
    }
    LifecycleResumeEffect(context) {
        batteryIgnoring = BatteryOptimization.isIgnoring(context)
        notifications = readNotificationAccess(context, notificationsAsked)
        onPauseOrDispose { }
    }
    OnboardingScreen(
        steps = steps,
        batteryIgnoring = batteryIgnoring,
        onRequestNotifications = {
            notificationsAsked = true
            actions.requestNotifications()
        },
        onRequestBattery = actions.requestBatteryExemption,
        onFinish = onFinish,
        notifications = notifications,
        onOpenNotificationSettings = { openNotificationSettings(context) },
        saveFailed = saveFailed,
    )
}

private fun readNotificationAccess(context: Context, asked: Boolean): NotificationAccess {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationAccess.GRANTED
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val rationale = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false
    return notificationAccess(granted, asked, rationale)
}

/** Ajustes de notificaciones de GhostP2 (API 26+, el `minSdk`). */
fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(notificationSettingsIntent(context))
    } catch (e: ActivityNotFoundException) {
        // Sin pantalla de ajustes de notificaciones en este sistema: no hay nada que abrir.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
