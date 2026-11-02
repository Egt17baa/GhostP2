package org.ghostp2.tor

import IPtProxy.Controller
import android.system.Os
import android.system.OsConstants
import java.io.File

// Lo único que toca IPtProxy (y, con él, go.Seq y libgojni.so). Solo se carga desde GoProcess.shared, es decir, con
// un arranque por puente; con «No» nunca.

/** `IPtProxy.newController` sin registros ni observador de eventos; `null` si Go devolvió un error. */
internal fun newIptProxyTransports(stateDir: File): GoTransports? {
    // newController, no el constructor: con un error de Go devuelve null en vez de un objeto inservible.
    val created = IPtProxy.IPtProxy.newController(stateDir.path, false, false, LOG_LEVEL, null) ?: return null
    // Con los registros desactivados nunca se crea; se borra por si acaso.
    File(stateDir, IPtProxy.IPtProxy.LogFileName).delete()
    return IptProxyTransports(created)
}

/**
 * Devuelve la salida estándar (fd 1) y la de errores (fd 2) del proceso a `/dev/null`, con `dup2`. Lanza
 * `ErrnoException` si falla (y entonces [GoProcess] falla cerrado).
 *
 * Por qué hace falta: el `init` de gomobile (`golang.org/x/mobile/internal/mobileinit`, enlazado en `libgojni.so`)
 * conecta los fd 1 y 2 a tuberías que copia en logcat con la etiqueta `GoLog`. Lo que pion (el WebRTC de Snowflake)
 * escribe en `os.Stderr` con su `LoggerFactory` por defecto (nivel Error: fallos de ICE, DTLS o SCTP, que pueden
 * incluir direcciones de voluntarios o candidatos) y cualquier traza de `panic` de Go acabarían en logcat.
 *
 * Por qué es seguro: `/dev/null` es lo que tienen esos fd en un proceso de app de Android (lo deja así zygote).
 * Java no los usa para registrar: `System.out`/`System.err` y `android.util.Log` escriben en logcat por su propio
 * camino (`RuntimeInit` sustituye `System.out`/`err`), no por los fd 1 y 2. Y Tor es otro proceso (un `exec` de
 * kmp-process con su propia entrada y salida), así que no le afecta. Queda un resto: los `log.Printf` de los caminos
 * de error del propio `NewController`, antes de configurar su log, van por `__android_log_write` (no por los fd) y
 * solo contienen la ruta de `stateDir`.
 */
internal fun redirectStdioToDevNull() {
    val devNull = Os.open("/dev/null", OsConstants.O_WRONLY, 0)
    try {
        // El FileDescriptor que devuelve dup2 es el fd 1 o 2 del proceso: no se cierra.
        Os.dup2(devNull, STDOUT_FD)
        Os.dup2(devNull, STDERR_FD)
    } finally {
        Os.close(devNull)
    }
}

private const val LOG_LEVEL = "ERROR"
private const val NO_UPSTREAM_PROXY = ""
private const val STDOUT_FD = 1
private const val STDERR_FD = 2

/** [GoTransports] sobre el `Controller` real. */
private class IptProxyTransports(private val controller: Controller) : GoTransports {
    override fun configureSnowflake(iceServers: String, brokerUrl: String, frontDomains: String) {
        controller.snowflakeIceServers = iceServers
        controller.snowflakeBrokerUrl = brokerUrl
        controller.snowflakeFrontDomains = frontDomains
        controller.snowflakeAmpCacheUrl = ""
        controller.snowflakeSqsUrl = ""
        controller.snowflakeSqsCreds = ""
    }

    override fun start(transport: String) = controller.start(transport, NO_UPSTREAM_PROXY)

    override fun stop(transport: String) = controller.stop(transport)

    override fun port(transport: String): Long = controller.port(transport)

    override fun localAddress(transport: String): String = controller.localAddress(transport)
}
