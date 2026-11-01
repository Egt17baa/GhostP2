package org.ghostp2.app.photo

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PhotoQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun uri(n: Int): Uri = Uri.parse("content://fotos/$n")

    /** Recoge los errores desde ya (suscrito antes de volver: sin carreras con el primer error). */
    private fun CoroutineScope.collectErrors(queue: PhotoQueue): MutableList<Pair<String, PhotoException.Reason>> {
        val errors = Collections.synchronizedList(mutableListOf<Pair<String, PhotoException.Reason>>())
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { queue.errors.collect { errors += it } }
        return errors
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(TIMEOUT) {
        while (!condition()) delay(5)
    }

    @Test
    fun rejectsBeyondCapacity() = runBlocking {
        val block = CompletableDeferred<Unit>()
        val queue = PhotoQueue(scope, encode = { block.await(); ByteArray(4) { 1 } }, send = { _, _ -> "id" }, awaitFinished = { _, _ -> })
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("a", uri(2)))
        assertTrue(queue.enqueue("b", uri(3)))
        // R25: 3 en total, contando la que está en vuelo.
        assertFalse(queue.enqueue("a", uri(4)))
        assertEquals(mapOf("a" to 2, "b" to 1), queue.preparing.value)
        block.complete(Unit)
        withTimeout(TIMEOUT) { queue.preparing.first { it.isEmpty() } }
        // Con la cola vacía vuelve a aceptar.
        assertTrue(queue.enqueue("a", uri(5)))
    }

    @Test
    fun sendsOneAtATime() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val finished = Channel<Unit>(Channel.UNLIMITED)
        val sent = AtomicInteger()
        val queue = PhotoQueue(
            scope,
            encode = { u ->
                // Comprobación determinista: al empezar la segunda, la primera ya terminó.
                if (u == uri(2)) events += "first finished before encode 2: ${"finished item1" in events}"
                events += "encode ${u.lastPathSegment}"
                ByteArray(4) { 1 }
            },
            send = { _, _ -> "item${sent.incrementAndGet()}".also { events += "send $it" } },
            awaitFinished = { _, id -> finished.receive(); events += "finished $id" },
        )
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("a", uri(2)))
        until { events.size >= 2 }
        finished.send(Unit)
        finished.send(Unit)
        until { events.size >= 7 }
        assertEquals(
            listOf(
                "encode 1", "send item1", "finished item1",
                "first finished before encode 2: true", "encode 2", "send item2", "finished item2",
            ),
            events.toList(),
        )
    }

    @Test
    fun wipesEncodedBytesAfterSend() = runBlocking {
        val encoded = ByteArray(16) { 7 }
        val seen = CompletableDeferred<ByteArray>()
        val done = CompletableDeferred<Unit>()
        val queue = PhotoQueue(
            scope,
            encode = { encoded },
            send = { _, jpeg -> seen.complete(jpeg.copyOf()); "id" },
            awaitFinished = { _, _ -> done.complete(Unit) },
        )
        assertTrue(queue.enqueue("a", uri(1)))
        withTimeout(TIMEOUT) { done.await() }
        assertArrayEquals(ByteArray(16) { 7 }, seen.await())
        assertArrayEquals(ByteArray(16), encoded)
    }

    @Test
    fun encodeFailureEmitsError() = runBlocking {
        val sends = AtomicInteger()
        val queue = PhotoQueue(
            scope,
            encode = { u -> if (u == uri(1)) throw PhotoException(PhotoException.Reason.TOO_LARGE) else throw IllegalStateException() },
            send = { _, _ -> sends.incrementAndGet(); "id" },
            awaitFinished = { _, _ -> },
        )
        val errors = collectErrors(queue)
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("b", uri(2)))
        until { errors.size >= 2 }
        assertEquals(listOf("a" to PhotoException.Reason.TOO_LARGE, "b" to PhotoException.Reason.UNREADABLE), errors.toList())
        assertEquals(0, sends.get())
        withTimeout(TIMEOUT) { queue.preparing.first { it.isEmpty() } }
        coroutineContext.cancelChildren()
    }

    @Test
    fun encodeThrowingErrorDoesNotWedgeQueue() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val queue = PhotoQueue(
            scope,
            encode = { u ->
                when (u) {
                    uri(1) -> throw OutOfMemoryError()
                    uri(2) -> throw AssertionError()
                    // Una cancelación que no viene de cancelar al trabajador (p. ej. un tiempo de espera interno).
                    uri(3) -> throw CancellationException("ajena")
                    else -> ByteArray(4) { 1 }
                }
            },
            send = { _, _ -> "id${sent.size}".also { sent += it } },
            awaitFinished = { _, _ -> },
        )
        val errors = collectErrors(queue)
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("b", uri(2)))
        assertTrue(queue.enqueue("c", uri(3)))
        until { errors.size >= 3 }
        assertEquals(
            listOf("a" to PhotoException.Reason.TOO_LARGE, "b" to PhotoException.Reason.UNREADABLE, "c" to PhotoException.Reason.UNREADABLE),
            errors.toList(),
        )
        withTimeout(TIMEOUT) { queue.preparing.first { it.isEmpty() } }
        // La cola sigue funcionando con su capacidad completa.
        assertTrue(queue.enqueue("a", uri(4)))
        until { sent.size == 1 }
        assertTrue(queue.enqueue("a", uri(5)))
        assertTrue(queue.enqueue("a", uri(6)))
        until { sent.size == 3 }
        coroutineContext.cancelChildren()
    }

    @Test
    fun notActiveEmitsError() = runBlocking {
        val encoded = ByteArray(8) { 3 }
        val awaited = AtomicInteger()
        val queue = PhotoQueue(scope, encode = { encoded }, send = { _, _ -> null }, awaitFinished = { _, _ -> awaited.incrementAndGet() })
        val errors = collectErrors(queue)
        assertTrue(queue.enqueue("a", uri(1)))
        until { errors.isNotEmpty() }
        assertEquals(listOf("a" to PhotoException.Reason.NOT_ACTIVE), errors.toList())
        assertEquals(0, awaited.get())
        assertArrayEquals(ByteArray(8), encoded)
        coroutineContext.cancelChildren()
    }

    @Test
    fun clearCancelsPending() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val encodes = Collections.synchronizedList(mutableListOf<Uri>())
        val sends = AtomicInteger()
        val queue = PhotoQueue(
            scope,
            encode = { u ->
                encodes += u
                if (u == uri(1)) {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                ByteArray(4) { 1 }
            },
            send = { _, _ -> sends.incrementAndGet(); "id" },
            awaitFinished = { _, _ -> },
        )
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("a", uri(2)))
        assertTrue(queue.enqueue("b", uri(3)))
        withTimeout(TIMEOUT) { started.await() }
        queue.clear()
        assertEquals(emptyMap<String, Int>(), queue.preparing.value)
        withTimeout(TIMEOUT) { cancelled.await() }
        // Determinista: la cola es FIFO, así que si 2 o 3 siguieran dentro se codificarían antes que 4.
        assertTrue(queue.enqueue("a", uri(4)))
        until { sends.get() == 1 }
        assertEquals(listOf(uri(1), uri(4)), encodes.toList())
    }

    @Test
    fun nothingIsSentAfterClear() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val encoded = ByteArray(8) { 5 }
        val sends = AtomicInteger()
        val queue = PhotoQueue(
            scope,
            // La codificación termina aunque se cancele (como PhotoEncoder bajo NonCancellable).
            encode = { entered.complete(Unit); withContext(NonCancellable) { gate.await() }; encoded },
            send = { _, _ -> sends.incrementAndGet(); "id" },
            awaitFinished = { _, _ -> },
        )
        assertTrue(queue.enqueue("a", uri(1)))
        withTimeout(TIMEOUT) { entered.await() }
        queue.clear()
        gate.complete(Unit)
        // Los bytes se borran al salir del intento de envío: después ya no puede enviarse.
        until { encoded.all { it == 0.toByte() } }
        assertEquals(0, sends.get())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
