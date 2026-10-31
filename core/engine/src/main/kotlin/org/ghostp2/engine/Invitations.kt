package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.protocol.FrameStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Estado de una invitación creada por este lado (invitador). */
sealed interface InvitationState {
    /**
     * Publicando el servicio temporal (todavía no hay texto que mostrar). Añadido a la interfaz del
     * plan: `createInvitation()` no es `suspend` y publicar en Tor sí lo es.
     */
    data object Preparing : InvitationState

    /** Lista para compartir `text` hasta `expiresAtMillis` (reloj de pared, `System.currentTimeMillis`). */
    data class Waiting(val text: String, val expiresAtMillis: Long) : InvitationState

    data class Paired(val contactId: String, val safetyNumber: String) : InvitationState

    data object Expired : InvitationState

    data object Cancelled : InvitationState

    data class Failed(val message: String) : InvitationState
}

/** Invitación activa: su estado y la forma de cancelarla (idempotente). */
class InvitationHandle(val state: StateFlow<InvitationState>, val cancel: () -> Unit)

/** Resultado de aceptar una invitación (lado invitado). */
sealed interface AcceptResult {
    data class Paired(val contactId: String, val safetyNumber: String) : AcceptResult

    data class Failed(val message: String) : AcceptResult
}

internal val InvitationState.isFinal: Boolean
    get() = this !is InvitationState.Preparing && this !is InvitationState.Waiting

/** Textos visibles de los fallos de invitación. */
internal object InvitationMessages {
    const val NOT_ACTIVE = "GhostP2 no está activo"
    const val CANNOT_CONNECT = "No se pudo conectar con la invitación (¿caducada o ya usada?)"
    const val MISMATCH = "La invitación no coincide"
    const val GENERIC = "No se pudo completar el emparejamiento"
    const val CANNOT_CREATE = "No se pudo crear la invitación"
    const val OWN_INVITATION = "No puedes aceptar tu propia invitación"
}

/**
 * Bucle del invitador sobre `server` (detrás del servicio temporal): acepta conexiones en
 * `Dispatchers.IO` y atiende cada una en su propia corrutina con `attempt` (el emparejamiento), sin
 * bloquear la aceptación. Cualquier fallo de un intento se descarta (se cierra su socket) y se sigue
 * esperando: los intentos fallidos no consumen la invitación. Como mucho `maxConcurrentAttempts`
 * intentos a la vez: las conexiones por encima se cierran nada más aceptarlas (evita agotar
 * descriptores).
 *
 * Solo un emparejamiento puede completarse: el primero que termina bien toma un guardián atómico y
 * ejecuta `complete` sin poder ser cancelado (para no dejar un contacto a medias); los demás que
 * terminen bien a la vez se entregan a `discard` (borrar sus secretos). Ese otro lado sí cree estar
 * emparejado: mismo caso residual que la trama "ok" perdida (PROTOCOLO §6), pero sin ninguna clave
 * válida para mi .onion.
 *
 * Devuelve el resultado de `complete`, o `null` si pasan `ttlMillis` sin que nadie gane. Al volver
 * (o al ser cancelado) cierra `server` y espera a que terminen todos los intentos en curso.
 * Si `complete` lanza, la excepción se propaga.
 */
internal suspend fun <A : Any, T : Any> serveInvitation(
    server: ServerSocket,
    ttlMillis: Long,
    maxConcurrentAttempts: Int,
    attempt: suspend (FrameStream) -> A,
    discard: (A) -> Unit,
    complete: suspend (A) -> T,
): T? = coroutineScope {
    val claimed = AtomicBoolean(false)
    val winner = CompletableDeferred<T>()
    val active = AtomicInteger(0)
    val loop = launch(Dispatchers.IO) {
        while (isActive) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break // ServerSocket cerrado
            }
            if (active.incrementAndGet() > maxConcurrentAttempts) {
                active.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            // ATOMIC: aunque se cancele antes de empezar, el finally cierra el socket y libera el cupo.
            launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
                try {
                    handleAttempt(socket, claimed, winner, attempt, discard, complete)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
    }
    try {
        // Si el plazo vence mientras el ganador termina de guardar el contacto, gana el emparejamiento.
        withTimeoutOrNull(ttlMillis) { winner.await() } ?: if (claimed.get()) winner.await() else null
    } finally {
        runCatching { server.close() }
        loop.cancel()
    }
}

private suspend fun <A : Any, T : Any> handleAttempt(
    socket: Socket,
    claimed: AtomicBoolean,
    winner: CompletableDeferred<T>,
    attempt: suspend (FrameStream) -> A,
    discard: (A) -> Unit,
    complete: suspend (A) -> T,
) {
    try {
        val stream = withContext(Dispatchers.IO) { FrameStream(socket.getInputStream(), socket.getOutputStream(), socket) }
        val result = attempt(stream)
        stream.close()
        if (!claimed.compareAndSet(false, true)) {
            discard(result)
            return
        }
        try {
            winner.complete(withContext(NonCancellable) { complete(result) })
        } catch (e: Exception) {
            winner.completeExceptionally(e)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Intento fallido (secreto incorrecto, conexión cortada, datos mal formados…): se ignora.
    } finally {
        runCatching { socket.close() }
    }
}
