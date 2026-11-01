package org.ghostp2.app.ui.onboarding

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ghostp2.app.BatteryOptimization
import org.ghostp2.app.R

/**
 * Bienvenida paso a paso ([steps], de [onboardingSteps]). Cada paso ofrece "Siguiente"; el último,
 * "Activar GhostP2" ([onFinish]). [batteryIgnoring] es el estado actual de la exención: con ella ya no se
 * ofrece pedirla; igual con [notifications] (y, denegado para siempre, se ofrecen los ajustes). Con
 * [saveFailed] se avisa junto a "Activar GhostP2". El paso actual vive solo en memoria (sin estado
 * guardable).
 */
@Composable
fun OnboardingScreen(
    steps: List<OnboardingStep>,
    batteryIgnoring: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onFinish: () -> Unit,
    notifications: NotificationAccess = NotificationAccess.ASK,
    onOpenNotificationSettings: () -> Unit = {},
    saveFailed: Boolean = false,
) {
    var index by remember(steps) { mutableIntStateOf(0) }
    val step = steps.getOrNull(index) ?: return
    val last = index == steps.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            OnboardingStep.WELCOME -> {
                Title(R.string.onboarding_welcome_title)
                listOf(
                    R.string.onboarding_welcome_no_servers,
                    R.string.onboarding_welcome_memory,
                    R.string.onboarding_welcome_discard,
                    R.string.onboarding_welcome_both_active,
                    R.string.onboarding_welcome_tor_slow,
                ).forEach { Body(it) }
            }
            OnboardingStep.NOTIFICATIONS -> {
                Title(R.string.onboarding_notifications_title)
                when (notifications) {
                    NotificationAccess.GRANTED -> Body(R.string.onboarding_notifications_done)
                    NotificationAccess.ASK -> {
                        Body(R.string.onboarding_notifications_text)
                        OutlinedButton(onClick = onRequestNotifications) {
                            Text(stringResource(R.string.onboarding_notifications_allow))
                        }
                    }
                    NotificationAccess.BLOCKED -> {
                        Body(R.string.onboarding_notifications_blocked)
                        OutlinedButton(onClick = onOpenNotificationSettings) {
                            Text(stringResource(R.string.onboarding_notifications_settings))
                        }
                    }
                }
            }
            OnboardingStep.BATTERY -> {
                Title(R.string.onboarding_battery_title)
                if (batteryIgnoring) {
                    Body(R.string.onboarding_battery_done)
                } else {
                    Body(R.string.onboarding_battery_text)
                    OutlinedButton(onClick = onRequestBattery) {
                        Text(stringResource(R.string.onboarding_battery_allow))
                    }
                }
                // El ahorro propio del fabricante sigue activo aunque Android exima a GhostP2.
                Body(BatteryOptimization.manufacturerHint(Build.MANUFACTURER))
            }
        }
        if (last && saveFailed) {
            Text(stringResource(R.string.store_failed), color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (last) {
                Button(onClick = onFinish) { Text(stringResource(R.string.onboarding_finish)) }
            } else {
                Button(onClick = { index++ }) { Text(stringResource(R.string.onboarding_next)) }
            }
        }
    }
}

@Composable
private fun Title(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}
