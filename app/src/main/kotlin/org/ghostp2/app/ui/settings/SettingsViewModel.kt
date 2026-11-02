package org.ghostp2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghostp2.app.AppContainer
import org.ghostp2.app.Notifications
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.bridges.BridgeLines
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.ParseResult

/**
 * Resultado de [SettingsViewModel.setBridges]: guardado (o sin cambios), pendiente de confirmar la reconexión
 * ([SettingsViewModel.confirmReconnect]) o rechazado porque «Mis puentes» no es válido (no se guarda nada).
 */
enum class BridgeChange { SAVED, NEEDS_RECONNECT, INVALID }

/** Ajuste «Puentes» pedido: [lines] (normalizadas) solo con [BridgeMode.CUSTOM]; con otro modo se conservan las guardadas. */
private data class BridgeTarget(val mode: BridgeMode, val lines: List<String>?) {
    fun differsFrom(current: AppSettings): Boolean =
        mode != current.bridgeMode || (lines != null && lines != current.customBridges)

    fun applyTo(current: AppSettings): AppSettings =
        current.copy(bridgeMode = mode, customBridges = lines ?: current.customBridges)
}

/**
 * Ajustes de la app sobre `container.settings`. Las escrituras van al scope del proceso (salir de la
 * pantalla no deja un ajuste a medias) y en el orden en que se piden. Si una falla, [saveFailed] y se
 * vuelve a comprobar el almacenamiento (si está mal, la raíz muestra el error).
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    /** Ajustes actuales; `null` mientras se leen (o si no se pueden leer: la raíz muestra el error). */
    val state: StateFlow<AppSettings?> = container.settings.settings
        .catch { container.checkStorage() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val mutableLockNeedsDeviceLock = MutableStateFlow(false)

    /** Se pidió activar el bloqueo sin bloqueo de pantalla en el móvil ("Configura un bloqueo de pantalla…"). */
    val lockNeedsDeviceLock: StateFlow<Boolean> = mutableLockNeedsDeviceLock.asStateFlow()

    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed.asStateFlow()

    private val mutableWiping = MutableStateFlow(false)

    /** "Borrar todo" en curso. */
    val wiping: StateFlow<Boolean> = mutableWiping.asStateFlow()

    private val mutableWipeFailed = MutableStateFlow(false)
    val wipeFailed: StateFlow<Boolean> = mutableWipeFailed.asStateFlow()

    /** Ordena las escrituras: cada una toma el cerrojo antes de devolver el control (UNDISPATCHED). */
    private val writes = Mutex()

    /**
     * Activa o desactiva "Bloqueo con huella o PIN". Activarlo exige bloqueo de pantalla en el móvil
     * ([AppContainer.deviceLockAvailable]); sin él devuelve `false`, no guarda nada y marca
     * [lockNeedsDeviceLock].
     */
    fun setLock(enabled: Boolean): Boolean {
        if (enabled && !container.deviceLockAvailable(container.appContext)) {
            mutableLockNeedsDeviceLock.value = true
            return false
        }
        mutableLockNeedsDeviceLock.value = false
        save { it.copy(lockEnabled = enabled) }
        return true
    }

    /** "Bloquear tras": uno de [AppSettings.LOCK_MINUTES]; otro valor se ignora. */
    fun setLockAfter(minutes: Int) {
        if (minutes in AppSettings.LOCK_MINUTES) save { it.copy(lockAfterMinutes = minutes) }
    }

    /** Al activarlo se quitan también las notificaciones de contactos ya publicadas (con nombre). */
    fun setHideNames(hide: Boolean) {
        if (hide) Notifications.cancelContactNotifications(container.appContext)
        save { it.copy(hideNotificationNames = hide) }
    }

    /** "Tamaño máximo" de las fotos: uno de [AppSettings.PHOTO_SIDES]; otro valor se ignora. */
    fun setMaxPhotoSide(px: Int) {
        if (px in AppSettings.PHOTO_SIDES) save { it.copy(maxPhotoSidePx = px) }
    }

    /** "Desconectar al salir de la app" (R29): uno de [AppSettings.DISCONNECT_MINUTES]; otro valor se ignora. */
    fun setDisconnectAfter(minutes: Int) {
        if (minutes in AppSettings.DISCONNECT_MINUTES) save { it.copy(disconnectAfterMinutes = minutes) }
    }

    /** "Activar GhostP2 al encender el móvil" (R20). */
    fun setStartOnBoot(on: Boolean) = save { it.copy(startOnBoot = on) }

    /**
     * "Borrar todo" ([AppContainer.wipeAll]) en el scope del proceso: la pantalla desaparece en cuanto
     * los ajustes vuelven a los de por defecto (la raíz pasa a la bienvenida) y el borrado no debe
     * cortarse por eso. Ignorado mientras otro está en curso.
     */
    fun wipeAll() {
        if (!mutableWiping.compareAndSet(expect = false, update = true)) return
        mutableWipeFailed.value = false
        container.scope.launch {
            try {
                container.wipeAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableWipeFailed.value = true
            } finally {
                mutableWiping.value = false
            }
        }
    }

    /** Cambio de «Puentes» a la espera de que se confirme la reconexión; solo en memoria. */
    @Volatile private var pendingBridges: BridgeTarget? = null

    /**
     * «Puentes» (R30). Con [BridgeMode.CUSTOM], [customText] es el texto pegado o escaneado: si no pasa
     * [BridgeLines.parse] o no tiene ninguna línea, [BridgeChange.INVALID] y no se guarda nada; si pasa, se
     * guardan sus líneas normalizadas. Con otro modo se conservan las líneas guardadas.
     *
     * Sin cambio real (mismo modo y, con «Mis puentes», las mismas líneas), [BridgeChange.SAVED] sin hacer nada.
     * Con cambio, según el motor:
     * - `Online` o `StartingTor`: [BridgeChange.NEEDS_RECONNECT] sin guardar; [confirmReconnect] guarda y reconecta
     *   y [cancelReconnect] lo olvida;
     * - `Error`: guarda y reconecta enseguida (no hay conversaciones que perder);
     * - `Stopped`: guarda y borra el estado de Tor.
     * Reconectar es parar, borrar el estado de Tor y volver a arrancar ([AppContainer.reconnect]), y cambiar el
     * ajuste siempre borra el estado de Tor, también al pasar a «No».
     */
    fun setBridges(mode: BridgeMode, customText: String?): BridgeChange {
        val lines = if (mode == BridgeMode.CUSTOM) {
            val parsed = BridgeLines.parse(customText.orEmpty())
            if (parsed !is ParseResult.Ok || parsed.lines.isEmpty()) return BridgeChange.INVALID
            parsed.lines.map { it.line }
        } else {
            null
        }
        val target = BridgeTarget(mode, lines)
        pendingBridges = null
        val current = state.value
        if (current != null && !target.differsFrom(current)) return BridgeChange.SAVED
        return when (container.engine.status.value) {
            EngineStatus.Online, is EngineStatus.StartingTor -> {
                pendingBridges = target
                BridgeChange.NEEDS_RECONNECT
            }
            else -> {
                applyBridges(target)
                BridgeChange.SAVED
            }
        }
    }

    /** "Reconectar": guarda el cambio pendiente de [setBridges] y reconecta. Sin cambio pendiente no hace nada. */
    fun confirmReconnect() {
        val target = pendingBridges ?: return
        pendingBridges = null
        applyBridges(target)
    }

    /** "Cancelar" en el diálogo de reconexión: se olvida el cambio pendiente (no se guarda). */
    fun cancelReconnect() {
        pendingBridges = null
    }

    /**
     * Guarda [target] y, si cambió algo, borra el estado de Tor: con el motor parado, solo eso; si no, reconecta.
     * En el scope del proceso y en orden con las demás escrituras; si no se puede guardar, [saveFailed] y nada más.
     */
    private fun applyBridges(target: BridgeTarget) {
        mutableSaveFailed.value = false
        container.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    if (!target.differsFrom(container.settings.get())) return@withLock
                    container.settings.update(target::applyTo)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableSaveFailed.value = true
                    container.checkStorage()
                    return@withLock
                }
                if (container.engine.status.value == EngineStatus.Stopped) {
                    container.forgetBridgeState()
                } else {
                    container.reconnect()
                }
            }
        }
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        mutableSaveFailed.value = false
        container.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    container.settings.update(transform)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableSaveFailed.value = true
                    container.checkStorage()
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
