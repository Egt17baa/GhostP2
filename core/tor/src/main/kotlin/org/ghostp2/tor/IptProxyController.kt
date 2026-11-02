package org.ghostp2.tor

import IPtProxy.Controller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.ghostp2.engine.bridges.BuiltInBridges
import org.ghostp2.engine.bridges.PtKind
import java.io.File
import java.io.IOException

/**
 * [PtController] real con IPtProxy 5.7.0 (Lyrebird para obfs4/webtunnel y Snowflake, en Go, dentro del proceso).
 *
 * **Única clase que toca `IPtProxy`.** [KmpTorTransport] solo la crea al arrancar con un ajuste de puentes distinto
 * de «No»; hasta entonces ni se carga `IPtProxy.Controller` ni, con él, `go.Seq`, que es quien hace
 * `System.loadLibrary("gojni")`.
 *
 * - **Un solo `Controller` de Go por proceso** ([GoController]): `NewController` llama a `transports.Init()` de
 *   Lyrebird, que falla («already registered») a partir de la segunda vez, y entonces IPtProxy devuelve `nil`.
 *   Cada instancia de esta clase (una por arranque de Tor) arranca y para sus transportes sobre ese único objeto.
 * - **Sin registros:** `enableLogging = false` (Lyrebird manda el log de Go a `io.Discard` y no crea
 *   `IPtProxy.LogFileName` en `stateDir`), `unsafeLogging = false` (el filtro de direcciones sigue activo) y
 *   `logLevel = "ERROR"`. Sin observador de eventos: no se pasa nada a Java desde los hilos de Go.
 * - **Snowflake:** los valores de tor-browser-build fijados en el `Controller` ([BuiltInBridges]): sin AMP cache
 *   ni SQS, así ningún valor interno de la librería (p. ej. `stun.l.google.com`) se cuela. Tor también los pasa
 *   en la línea `Bridge`, que tiene prioridad.
 * - **Solo 127.0.0.1:** `start(transporte, "")` (sin proxy delante) abre un SOCKS en `127.0.0.1:0`; se
 *   comprueba con `localAddress` antes de devolver el puerto.
 * - `stateDir` (`noBackupFilesDir/pt`): obfs4, webtunnel y Snowflake no guardan nada en él como clientes; IPtProxy
 *   solo prueba al crear el `Controller` que se puede escribir. [KmpTorTransport] lo borra en cada parada.
 */
internal class IptProxyController(private val stateDir: File) : PtController {

    /** Transportes arrancados por esta instancia; protegido por [GoController]. */
    private var started: Set<PtKind> = emptySet()

    /**
     * Siempre vacío con IPtProxy 5.7.0: sus eventos son **por conexión**, no por transporte. `Stopped` se emite al
     * terminar cada conexión SOCKS de Tor (también si un puente concreto no responde) y `Error` en cada intento
     * fallido de Snowflake de encontrar un voluntario, que la librería reintenta sola. Tor ya prueba los demás
     * puentes; convertirlos en un fallo global tiraría una conexión que funciona cuando uno de los 7 puentes
     * integrados está caído. Que ningún puente funcione se detecta por el plazo de arranque del motor
     * (`BRIDGE_FAILED`). El transporte en sí (un bucle de Go en este proceso) no puede morir solo.
     */
    override val failures: Flow<PtKind> = emptyFlow()

    override suspend fun start(kinds: Set<PtKind>): Map<PtKind, Int> = withContext(Dispatchers.IO) {
        synchronized(GoController) {
            check(started.isEmpty()) { "Transportes ya arrancados" }
            val controller = try {
                GoController.get(stateDir)
            } catch (e: LinkageError) {
                // Sin libgojni.so para esta ABI (o no carga): falla cerrado, como cualquier fallo del transporte.
                throw IOException("IPtProxy no disponible")
            }
            controller.snowflakeIceServers = BuiltInBridges.SNOWFLAKE_ICE
            controller.snowflakeBrokerUrl = BuiltInBridges.SNOWFLAKE_BROKER
            controller.snowflakeFrontDomains = BuiltInBridges.SNOWFLAKE_FRONTS
            controller.snowflakeAmpCacheUrl = ""
            controller.snowflakeSqsUrl = ""
            controller.snowflakeSqsCreds = ""
            val ports = LinkedHashMap<PtKind, Int>()
            try {
                for (kind in kinds) {
                    // Por si quedó uno de un arranque anterior que no se paró (no debería): IPtProxy pisaría su
                    // escucha sin cerrarla. Parar uno que no está en marcha no hace nada.
                    controller.stop(kind.torName)
                    started = started + kind
                    controller.start(kind.torName, NO_UPSTREAM_PROXY)
                    val port = controller.port(kind.torName)
                    if (port !in 1L..65_535L || controller.localAddress(kind.torName) != "$LOOPBACK:$port") {
                        throw IOException("Transporte del puente fuera de $LOOPBACK")
                    }
                    ports[kind] = port.toInt()
                }
            } catch (e: CancellationException) {
                stopStarted(controller)
                throw e
            } catch (e: Exception) {
                stopStarted(controller)
                // Sin el mensaje de la librería: podría incluir el puente.
                throw IOException("No se pudo iniciar el transporte del puente (${e.javaClass.simpleName})")
            }
            ports
        }
    }

    override fun stop() {
        synchronized(GoController) {
            GoController.existing()?.let { stopStarted(it) }
            started = emptySet()
        }
    }

    private fun stopStarted(controller: Controller) {
        for (kind in started) {
            try {
                controller.stop(kind.torName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Mejor esfuerzo: los demás también se paran.
            }
        }
        started = emptySet()
    }

    /** El `Controller` de Go del proceso; todo acceso, con su cerrojo (el de este objeto). */
    private object GoController {
        private var controller: Controller? = null

        fun existing(): Controller? = controller

        fun get(stateDir: File): Controller {
            controller?.let { return it }
            if (!stateDir.isDirectory && !stateDir.mkdirs()) throw IOException("No se pudo crear el estado del puente")
            // newController, no el constructor: con un error de Go devuelve null en vez de un objeto inservible.
            val created = IPtProxy.IPtProxy.newController(stateDir.path, false, false, LOG_LEVEL, null)
                ?: throw IOException("No se pudo iniciar IPtProxy")
            // Con los registros desactivados nunca se crea; se borra por si acaso.
            File(stateDir, IPtProxy.IPtProxy.LogFileName).delete()
            controller = created
            return created
        }
    }

    private companion object {
        const val LOG_LEVEL = "ERROR"
        const val NO_UPSTREAM_PROXY = ""
        const val LOOPBACK = "127.0.0.1"
    }
}
