package org.ghostp2.core.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Dos [FrameStream] conectados por sockets TCP de bucle local. No se usan tuberías
 * (PipedInputStream) porque fallan entre hilos de Dispatchers.IO ("Write end dead").
 */
class TestTransport : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val clientSocket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
    private val serverSocket = server.accept()

    /** Extremo A (cliente) y su socket subyacente, por si una prueba necesita bytes crudos. */
    val rawA: Socket get() = clientSocket
    val a = FrameStream(clientSocket.getInputStream(), clientSocket.getOutputStream(), clientSocket)
    val b = FrameStream(serverSocket.getInputStream(), serverSocket.getOutputStream(), serverSocket)

    override fun close() {
        runCatching { clientSocket.close() }
        runCatching { serverSocket.close() }
        runCatching { server.close() }
    }
}

/** Como assertThrows, pero admite llamadas suspendidas dentro del bloque. */
suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Se esperaba ${T::class.simpleName} y se lanzó $e", e)
    }
    throw AssertionError("Se esperaba ${T::class.simpleName} y no se lanzó nada")
}

/**
 * Abre una sesión KKpsk2 real entre dos contactos recién emparejados sobre [a] (iniciador) y
 * [b] (respondedor) y devuelve sus dos canales, ya con la confirmación de clave consumida.
 */
fun pairedChannels(a: FrameStream, b: FrameStream): Pair<RatchetChannel, RatchetChannel> = runBlocking {
    val alice = Primitives.generateX25519()
    val bob = Primitives.generateX25519()
    val secret = Primitives.randomBytes(32)
    val i = async { SessionHandshake.initiate(a, alice, ContactKeys(bob.publicKey.copyOf(), secret.copyOf())) }
    val r = async { SessionHandshake.respond(b, bob, listOf(ContactKeys(alice.publicKey.copyOf(), secret.copyOf()))) }
    i.await() to r.await().second
}
