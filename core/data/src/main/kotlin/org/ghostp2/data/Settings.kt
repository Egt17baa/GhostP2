package org.ghostp2.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.bridgeSetupFor

/**
 * Ajustes de la app, guardados en `ghostp2.bin` junto a la identidad y los contactos (formato 2, R21).
 * Solo opciones de la interfaz: ningún secreto ni contenido.
 */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val lockEnabled: Boolean = false,
    /** Minutos en segundo plano antes de volver a pedir el desbloqueo; uno de [LOCK_MINUTES]. */
    val lockAfterMinutes: Int = 1,
    val hideNotificationNames: Boolean = false,
    /** Lado mayor de las fotos enviadas, en píxeles; uno de [PHOTO_SIDES]. */
    val maxPhotoSidePx: Int = 2048,
    /** "Activar GhostP2 al encender el móvil" (R20), desactivado por defecto. */
    val startOnBoot: Boolean = false,
    /**
     * "Desconectar al salir de la app" (R29): minutos en segundo plano antes de parar todo; uno de
     * [DISCONNECT_MINUTES], 0 = "Nunca" (por defecto).
     */
    val disconnectAfterMinutes: Int = 0,
    /** "Puentes" (R30): [BridgeMode.NONE] (por defecto) = conexión directa a Tor. */
    val bridgeMode: BridgeMode = BridgeMode.NONE,
    /**
     * Líneas de "Mis puentes" tal como se guardaron. Se conservan con cualquier modo, para no perderlas al cambiar
     * de opción; solo se usan con [BridgeMode.CUSTOM].
     */
    val customBridges: List<String> = emptyList(),
) {
    /**
     * Los mismos ajustes con cada valor fuera de su lista sustituido por el de por defecto. [BridgeMode.CUSTOM] sin
     * [customBridges] válidas (vacías o que no pasan `BridgeLines.parse`) pasa a [BridgeMode.NONE] sin líneas.
     */
    fun sanitized(): AppSettings {
        val lock = if (lockAfterMinutes in LOCK_MINUTES) lockAfterMinutes else DEFAULT.lockAfterMinutes
        val side = if (maxPhotoSidePx in PHOTO_SIDES) maxPhotoSidePx else DEFAULT.maxPhotoSidePx
        val disconnect =
            if (disconnectAfterMinutes in DISCONNECT_MINUTES) disconnectAfterMinutes else DEFAULT.disconnectAfterMinutes
        val bridgesOk = bridgeMode != BridgeMode.CUSTOM || bridgeSetupFor(bridgeMode, customBridges) != null
        return if (lock == lockAfterMinutes && side == maxPhotoSidePx && disconnect == disconnectAfterMinutes && bridgesOk) {
            this
        } else {
            copy(
                lockAfterMinutes = lock,
                maxPhotoSidePx = side,
                disconnectAfterMinutes = disconnect,
                bridgeMode = if (bridgesOk) bridgeMode else DEFAULT.bridgeMode,
                customBridges = if (bridgesOk) customBridges else DEFAULT.customBridges,
            )
        }
    }

    companion object {
        val LOCK_MINUTES = listOf(0, 1, 5, 15)
        val PHOTO_SIDES = listOf(1280, 2048, 3072)

        /** Valores de "Desconectar al salir de la app"; 0 = "Nunca". */
        val DISCONNECT_MINUTES = listOf(0, 2, 5, 15)
        private val DEFAULT = AppSettings()
    }
}

/**
 * Ajustes persistentes. Las dos lecturas devuelven siempre [AppSettings.sanitized]. Los errores del
 * almacenamiento ([StoreCorruptedException], [StoreUnavailableException]) llegan tal cual: nunca se
 * sustituyen por los ajustes por defecto.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun get(): AppSettings

    /**
     * Aplica `transform` a los ajustes actuales y los guarda. `transform` debe ser pura: se ejecuta con
     * el cerrojo del almacén tomado (no reentrante) y nunca debe llamar a ningún repositorio.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    /** Vuelve a los ajustes por defecto; sobre un almacén dañado lo borra entero (como los otros `wipe()`). */
    suspend fun wipe()
}

/** Ajustes sobre el almacén compartido `ghostp2.bin`. */
class StoredSettingsRepository(private val store: EncryptedStore<StoredData>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data
        .map { it.appSettings() }
        .distinctUntilChanged()

    override suspend fun get(): AppSettings = store.read().appSettings()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.update { data ->
            val current = data.appSettings()
            val next = transform(current).sanitized()
            if (next == current) data else data.withSettings(StoredSettings.from(next))
        }
    }

    override suspend fun wipe() {
        store.updateOrReset { if (it.settings == null) it else it.withSettings(null) }
    }
}
