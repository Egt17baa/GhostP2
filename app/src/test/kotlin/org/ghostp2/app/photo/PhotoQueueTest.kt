package org.ghostp2.app.photo

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    @Test
    fun rejectsBeyondCapacity() = runBlocking {
        val block = CompletableDeferred<Unit>()
        val queue = PhotoQueue(scope, encode = { block.await(); ByteArray(4) { 1 } }, send = { _, _ -> "id" }, awaitFinished = { _, _ -> })
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("a", uri(2)))
        assertTrue(queue.enqueue("b", uri(3)))
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
            encode = { u -> events += "encode ${u.lastPathSegment}"; ByteArray(4) { 1 } },
            send = { _, _ -> "item${sent.incrementAndGet()}".also { events += "send $it" } },
            awaitFinished = { _, id -> finished.receive(); events += "finished $id" },
        )
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("a", uri(2)))
        withTimeout(TIMEOUT) { while (events.size < 2) delay(10) }
        delay(200)
        // El segundo no se codifica mientras el primero sigue en vuelo.
        assertEquals(listOf("encode 1", "send item1"), events.toList())
        finished.send(Unit)
        finished.send(Unit)
        withTimeout(TIMEOUT) { while (events.size < 6) delay(10) }
        assertEquals(listOf("encode 1", "send item1", "finished item1", "encode 2", "send item2", "finished item2"), events.toList())
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
        val errors = Collections.synchronizedList(mutableListOf<Pair<String, PhotoException.Reason>>())
        val collector = launch(Dispatchers.Default) { queue.errors.collect { errors += it } }
        delay(100)
        assertTrue(queue.enqueue("a", uri(1)))
        assertTrue(queue.enqueue("b", uri(2)))
        withTimeout(TIMEOUT) { while (errors.size < 2) delay(10) }
        assertEquals(listOf("a" to PhotoException.Reason.TOO_LARGE, "b" to PhotoException.Reason.UNREADABLE), errors.toList())
        assertEquals(0, sends.get())
        withTimeout(TIMEOUT) { queue.preparing.first { it.isEmpty() } }
        collector.cancel()
    }

    @Test
    fun notActiveEmitsError() = runBlocking {
        val encoded = ByteArray(8) { 3 }
        val awaited = AtomicInteger()
        val queue = PhotoQueue(scope, encode = { encoded }, send = { _, _ -> null }, awaitFinished = { _, _ -> awaited.incrementAndGet() })
        val error = CompletableDeferred<Pair<String, PhotoException.Reason>>()
        val collector = launch(Dispatchers.Default) { queue.errors.collect { error.complete(it) } }
        delay(100)
        assertTrue(queue.enqueue("a", uri(1)))
        assertEquals("a" to PhotoException.Reason.NOT_ACTIVE, withTimeout(TIMEOUT) { error.await() })
        assertEquals(0, awaited.get())
        assertArrayEquals(ByteArray(8), encoded)
        collector.cancel()
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
        withTimeout(TIMEOUT) { cancelled.await() }
        assertEquals(emptyMap<String, Int>(), queue.preparing.value)
        delay(200)
        assertEquals(listOf(uri(1)), encodes.toList())
        assertEquals(0, sends.get())
        // Tras vaciarla funciona de nuevo con su capacidad completa.
        assertTrue(queue.enqueue("a", uri(4)))
        withTimeout(TIMEOUT) { while (sends.get() < 1) delay(10) }
        assertEquals(listOf(uri(1), uri(4)), encodes.toList())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
