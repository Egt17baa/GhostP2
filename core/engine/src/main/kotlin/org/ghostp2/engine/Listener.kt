package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Escucha local del servicio oculto: `ServerSocket` en `127.0.0.1:0` (puerto aleatorio, nunca en otras
 * interfaces). El bucle de aceptación corre en `scope` sobre `Dispatchers.IO` y entrega cada [Socket]
 * a `onAccept`, que pasa a ser su dueño. Si `onAccept` lanza, se cierra el socket y se sigue aceptando.
 * [close]/[closeAndJoin] cierran el `ServerSocket` (lo que desbloquea `accept()`) y terminan el bucle.
 */
internal class Listener(scope: CoroutineScope, private val onAccept: (Socket) -> Unit) {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))

    /** Puerto local asignado. */
    val port: Int = server.localPort

    /** Trabajo del bucle de aceptación (hijo del scope recibido). */
    val job: Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break // ServerSocket cerrado
            }
            try {
                onAccept(socket)
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Cierra el listener; idempotente. Con el `ServerSocket` bloqueado en `accept()` en otro hilo, el
     * puerto puede seguir aceptando un instante: usar [closeAndJoin] para tener la garantía de cierre.
     */
    fun close() {
        runCatching { server.close() }
    }

    /** Cierra y espera a que termine el bucle de aceptación (el puerto queda cerrado al volver). */
    suspend fun closeAndJoin() {
        close()
        job.join()
    }

    private companion object {
        const val BACKLOG = 50
    }
}
