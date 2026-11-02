package org.ghostp2.core.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.nio.ByteBuffer
import kotlin.random.Random

class FrameStreamTest {
    private val transport = TestTransport()

    @AfterEach
    fun tearDown() = transport.close()

    private fun rawWrite(bytes: ByteArray) {
        transport.rawA.getOutputStream().apply { write(bytes); flush() }
    }

    private fun prefix(len: Int) = ByteBuffer.allocate(4).putInt(len).array()

    @Test
    fun framesRoundTrip(): Unit = runBlocking {
        val small = byteArrayOf(42)
        val big = Random.nextBytes(FrameStream.MAX_FRAME)
        transport.a.send(small)
        transport.a.send(big)
        assertArrayEquals(small, transport.b.receive())
        assertArrayEquals(big, transport.b.receive())
        // Y en sentido contrario
        transport.b.send(big)
        assertArrayEquals(big, transport.a.receive())
    }

    @Test
    fun rejectsOversizedLengthPrefix(): Unit = runBlocking {
        rawWrite(prefix(0x7fffffff))
        assertThrowsSuspend<ProtocolException> { transport.b.receive() }
    }

    @Test
    fun rejectsZeroLengthPrefix(): Unit = runBlocking {
        rawWrite(prefix(0))
        assertThrowsSuspend<ProtocolException> { transport.b.receive() }
    }

    @Test
    fun rejectsNegativeLengthPrefix(): Unit = runBlocking {
        rawWrite(prefix(-1))
        assertThrowsSuspend<ProtocolException> { transport.b.receive() }
    }

    @Test
    fun rejectsJustOverMaxLengthPrefix(): Unit = runBlocking {
        rawWrite(prefix(FrameStream.MAX_FRAME + 1))
        assertThrowsSuspend<ProtocolException> { transport.b.receive() }
    }

    @Test
    fun eofWhenClosedBeforeFrame(): Unit = runBlocking {
        transport.a.close()
        assertThrowsSuspend<EOFException> { transport.b.receive() }
    }

    @Test
    fun eofWhenClosedMidFrame(): Unit = runBlocking {
        rawWrite(prefix(100) + ByteArray(10))
        transport.a.close()
        assertThrowsSuspend<EOFException> { transport.b.receive() }
    }

    @Test
    fun sendRejectsOversizedAndEmptyFrames(): Unit = runBlocking {
        assertThrowsSuspend<IllegalArgumentException> { transport.a.send(ByteArray(FrameStream.MAX_FRAME + 1)) }
        assertThrowsSuspend<IllegalArgumentException> { transport.a.send(ByteArray(0)) }
    }

    @Test
    fun concurrentSendsDoNotInterleave(): Unit = runBlocking {
        val frames = (1..40).map { i -> ByteArray(4_000 + i) { i.toByte() } }
        val sender = async { frames.map { f -> async { transport.a.send(f) } }.awaitAll() }
        val received = (1..frames.size).map { transport.b.receive() }
        sender.await()
        // Cada trama llega entera y homogénea (sin bytes de otra trama mezclados)
        val sizes = received.map { r ->
            assertEquals(1, r.toSet().size)
            assertEquals(4_000 + r[0], r.size)
            r.size
        }
        assertEquals(frames.map { it.size }.sorted(), sizes.sorted())
    }
}
