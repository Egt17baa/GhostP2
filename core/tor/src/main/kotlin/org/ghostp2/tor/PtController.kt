package org.ghostp2.tor

import kotlinx.coroutines.flow.Flow
import org.ghostp2.engine.bridges.PtKind

/**
 * Transportes disfrazados (pluggable transports) para **un** arranque de Tor por puente (R30): [KmpTorTransport]
 * crea uno con cada arranque por puente y lo para en cada parada. La implementación real es [IptProxyController];
 * las pruebas usan un doble.
 */
internal interface PtController {
    /**
     * Arranca cada transporte de [kinds] y devuelve su puerto SOCKS local, siempre en 127.0.0.1. Lanza
     * [java.io.IOException] si alguno falla; entonces no deja ninguno en marcha.
     */
    suspend fun start(kinds: Set<PtKind>): Map<PtKind, Int>

    /** Para todos los transportes que arrancó. Idempotente; no lanza. */
    fun stop()

    /** Fallo de un transporte entero ya arrancado (no de una conexión suelta): Tor ya no puede usarlo. */
    val failures: Flow<PtKind>
}
