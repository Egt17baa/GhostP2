package org.ghostp2.app.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bloqueo de la app (R22): una puerta de la interfaz, no cifra nada (la clave de Keystore no exige
 * autenticación). Vive lo que el proceso, en el [org.ghostp2.app.AppContainer].
 *
 * - Hasta la primera [configure] está bloqueado (falla cerrado): la raíz no muestra nada sensible
 *   aunque los ajustes le lleguen antes que aquí.
 * - La **primera** [configure] del proceso con `enabled` bloquea (arranque en frío); sin `enabled`,
 *   desbloquea. Las siguientes solo cambian los parámetros, salvo `enabled = false`, que desbloquea.
 * - [onStart] bloquea si está activado y pasaron al menos `afterMinutes` desde el último [onStop]
 *   (0 = siempre). Un [onStop] por cambio de configuración (girar el móvil) no cuenta.
 *
 * El reloj es `elapsedRealtime`: cuenta también el tiempo con el móvil dormido y no cambia con la hora.
 */
class AppLock(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val mutableLocked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    private val mutableNotice = MutableStateFlow(false)

    /** Se desactivó el bloqueo porque el móvil ya no tiene bloqueo de pantalla: la raíz lo avisa. */
    val missingDeviceLockNotice: StateFlow<Boolean> = mutableNotice.asStateFlow()

    // Protegidos por `this`.
    private var configured = false
    private var afterMillis = 0L
    private var stoppedAt: Long? = null

    /** Bloqueo activado según la última [configure] (o desactivado por [disableForMissingDeviceLock]). */
    @get:Synchronized
    var enabled = false
        private set

    @Synchronized
    fun configure(enabled: Boolean, afterMinutes: Int) {
        val first = !configured
        configured = true
        this.enabled = enabled
        afterMillis = afterMinutes.coerceAtLeast(0) * MILLIS_PER_MINUTE
        when {
            !enabled -> mutableLocked.value = false
            first -> mutableLocked.value = true
        }
    }

    @Synchronized
    fun onStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) stoppedAt = clock()
    }

    @Synchronized
    fun onStart() {
        val since = stoppedAt ?: return
        stoppedAt = null
        if (enabled && clock() - since >= afterMillis) mutableLocked.value = true
    }

    @Synchronized
    fun unlock() {
        mutableLocked.value = false
    }

    /**
     * El móvil ya no tiene bloqueo de pantalla: GhostP2 no puede quedar inaccesible. Desbloquea, deja de
     * bloquear (hasta que los ajustes digan otra cosa) y levanta [missingDeviceLockNotice]. Guardar
     * `lockEnabled = false` es cosa del llamante ([org.ghostp2.app.AppContainer.disableLockForMissingDeviceLock]).
     */
    @Synchronized
    fun disableForMissingDeviceLock() {
        enabled = false
        mutableLocked.value = false
        mutableNotice.value = true
    }

    fun missingDeviceLockNoticeShown() {
        mutableNotice.value = false
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
