package org.ghostp2.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
) {
    /** Los mismos ajustes con cada valor fuera de su lista sustituido por el de por defecto. */
    fun sanitized(): AppSettings {
        val lock = if (lockAfterMinutes in LOCK_MINUTES) lockAfterMinutes else DEFAULT.lockAfterMinutes
        val side = if (maxPhotoSidePx in PHOTO_SIDES) maxPhotoSidePx else DEFAULT.maxPhotoSidePx
        return if (lock == lockAfterMinutes && side == maxPhotoSidePx) this
        else copy(lockAfterMinutes = lock, maxPhotoSidePx = side)
    }

    companion object {
        val LOCK_MINUTES = listOf(0, 1, 5, 15)
        val PHOTO_SIDES = listOf(1280, 2048, 3072)
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
