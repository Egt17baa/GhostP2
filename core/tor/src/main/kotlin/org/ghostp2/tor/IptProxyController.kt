package org.ghostp2.tor

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
 * **Solo `IptProxyGo.kt` toca `IPtProxy`** (detrás de [GoTransports] y [GoProcess], para probar esta clase en la
 * JVM). [KmpTorTransport] solo crea esta clase al arrancar con un ajuste de puentes distinto de «No»; hasta entonces
 * ni se carga `IPtProxy.Controller` ni, con él, `go.Seq`, que es quien hace `System.loadLibrary("gojni")`.
 *
 * - **Un solo `Controller` de Go por proceso** ([GoProcess.shared]): `NewController` llama a `transports.Init()` de
 *   Lyrebird, que falla («already registered») a partir de la segunda vez, y entonces IPtProxy devuelve `nil`.
 *   Cada instancia de esta clase (una por arranque de Tor) arranca y para sus transportes sobre ese único objeto.
 * - **Sin registros:** `enableLogging = false` (Lyrebird manda el log de Go a `io.Discard` y no crea
 *   `IPtProxy.LogFileName` en `stateDir`), `unsafeLogging = false` (el filtro de direcciones sigue activo) y
 *   `logLevel = "ERROR"`. Sin observador de eventos: no se pasa nada a Java desde los hilos de Go.
 * - **Sin salida a logcat:** gomobile lleva los fd 1 y 2 del proceso a logcat (`GoLog`); antes de arrancar ningún
 *   transporte se devuelven a `/dev/null` (ver [GoProcess]). Si no se puede, el arranque falla cerrado.
 * - **Snowflake:** los valores de tor-browser-build fijados en el `Controller` ([BuiltInBridges]): sin AMP cache
 *   ni SQS, así ningún valor interno de la librería (p. ej. `stun.l.google.com`) se cuela. Tor también los pasa
 *   en la línea `Bridge`, que tiene prioridad.
 * - **Solo 127.0.0.1:** `start(transporte, "")` (sin proxy delante) abre un SOCKS en `127.0.0.1:0`; se
 *   comprueba con `localAddress` antes de devolver el puerto.
 * - **Cualquier fallo** de IPtProxy, también un `LinkageError` de JNI (p. ej. `UnsatisfiedLinkError` con un
 *   `libgojni.so` roto) o de la creación del `Controller`, sale como [IOException] tras parar los transportes ya
 *   arrancados por esta instancia: el arranque por puente falla cerrado.
 * - `stateDir` (`noBackupFilesDir/pt`): obfs4, webtunnel y Snowflake no guardan nada en él como clientes; IPtProxy
 *   solo prueba al crear el `Controller` que se puede escribir. [KmpTorTransport] lo borra en cada parada.
 */
internal class IptProxyController(
    private val stateDir: File,
    private val go: GoProcess = GoProcess.shared,
) : PtController {

    /** Transportes arrancados por esta instancia; protegido por el cerrojo de [go]. */
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
        synchronized(go) {
            check(started.isEmpty()) { "Transportes ya arrancados" }
            try {
                startLocked(kinds)
            } catch (e: CancellationException) {
                go.existing()?.let { stopStarted(it) }
                throw e
            } catch (e: Throwable) {
                go.existing()?.let { stopStarted(it) }
                started = emptySet()
                // Sin el mensaje de la librería: podría incluir el puente.
                throw e as? PtStartException
                    ?: PtStartException("No se pudo iniciar el transporte del puente (${e.javaClass.simpleName})")
            }
        }
    }

    private fun startLocked(kinds: Set<PtKind>): Map<PtKind, Int> {
        val controller = go.get(stateDir)
        controller.configureSnowflake(
            BuiltInBridges.SNOWFLAKE_ICE,
            BuiltInBridges.SNOWFLAKE_BROKER,
            BuiltInBridges.SNOWFLAKE_FRONTS,
        )
        val ports = LinkedHashMap<PtKind, Int>()
        for (kind in kinds) {
            // Por si quedó uno de un arranque anterior que no se paró (no debería): IPtProxy pisaría su escucha
            // sin cerrarla. Parar uno que no está en marcha no hace nada.
            controller.stop(kind.torName)
            started = started + kind
            controller.start(kind.torName)
            val port = controller.port(kind.torName)
            if (port !in 1L..65_535L || controller.localAddress(kind.torName) != "$LOOPBACK:$port") {
                throw PtStartException("Transporte del puente fuera de $LOOPBACK")
            }
            ports[kind] = port.toInt()
        }
        return ports
    }

    override fun stop() {
        synchronized(go) {
            go.existing()?.let { stopStarted(it) }
            started = emptySet()
        }
    }

    /** Para lo que arrancó esta instancia; nunca lanza (salvo cancelación). */
    private fun stopStarted(controller: GoTransports) {
        for (kind in started) {
            try {
                controller.stop(kind.torName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Mejor esfuerzo: los demás también se paran (también con un LinkageError de JNI).
            }
        }
        started = emptySet()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/** Fallo de arranque de un transporte, con un mensaje propio (nunca el de la librería). */
internal class PtStartException(message: String) : IOException(message)

/**
 * Las llamadas a un `IPtProxy.Controller` que usa [IptProxyController] (la real, en `IptProxyGo.kt`; las pruebas
 * usan un doble). Todas pueden lanzar cualquier `Throwable` (errores de Go o de JNI).
 */
internal interface GoTransports {
    /** Fija los valores de Snowflake y deja vacíos AMP cache y SQS. */
    fun configureSnowflake(iceServers: String, brokerUrl: String, frontDomains: String)

    /** Arranca el transporte sin proxy delante (escucha SOCKS en `127.0.0.1:0`). */
    fun start(transport: String)

    fun stop(transport: String)

    fun port(transport: String): Long

    fun localAddress(transport: String): String
}

/**
 * El `Controller` de Go del proceso (IPtProxy solo permite crear uno). Todo acceso, con el cerrojo de este objeto.
 *
 * [create] envuelve `IPtProxy.newController` (`null` con un error de Go). Justo después del primer `create` que
 * funciona, y antes de arrancar ningún transporte, [silenceStdio] devuelve los fd 1 y 2 del proceso a `/dev/null`
 * **una sola vez por proceso** (ver [redirectStdioToDevNull]): `newController` es una llamada exportada de Go, así
 * que para entonces ya se ejecutaron los `init` de los paquetes de Go, incluido el de gomobile (`mobileinit`), que
 * conecta esos fd a logcat. Si la redirección falla, [get] lanza [IOException] (el arranque por puente falla cerrado)
 * y la reintenta en la siguiente llamada sobre el mismo `Controller`, que se conserva (no se puede crear otro).
 */
internal class GoProcess(
    private val create: (stateDir: File) -> GoTransports?,
    private val silenceStdio: () -> Unit,
) {
    private var transports: GoTransports? = null
    private var silenced = false

    /** El `Controller` ya creado (aunque no se haya podido silenciar), para parar transportes. */
    fun existing(): GoTransports? = transports

    /** El `Controller` del proceso, con la salida estándar ya en `/dev/null`; si no, [IOException]. */
    fun get(stateDir: File): GoTransports {
        val current = transports ?: createLocked(stateDir).also { transports = it }
        if (!silenced) {
            try {
                silenceStdio()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                throw PtStartException("No se pudo apartar la salida de IPtProxy de logcat")
            }
            silenced = true
        }
        return current
    }

    private fun createLocked(stateDir: File): GoTransports {
        if (!stateDir.isDirectory && !stateDir.mkdirs()) throw PtStartException("No se pudo crear el estado del puente")
        return try {
            create(stateDir)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin libgojni.so para esta ABI, o no carga (LinkageError, ExceptionInInitializerError): falla cerrado.
            throw PtStartException("IPtProxy no disponible")
        } ?: throw PtStartException("No se pudo iniciar IPtProxy")
    }

    companion object {
        /** El del proceso, con IPtProxy de verdad; solo se crea (y se carga IPtProxy) al usarlo. */
        val shared: GoProcess by lazy { GoProcess(::newIptProxyTransports, ::redirectStdioToDevNull) }
    }
}
