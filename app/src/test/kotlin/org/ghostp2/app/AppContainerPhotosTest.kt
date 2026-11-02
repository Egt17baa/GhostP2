package org.ghostp2.app

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.photo.PhotoException
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.Direction
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cómo conecta el [AppContainer] la cola de fotos con el motor. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppContainerPhotosTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp

    @After
    fun tearDown() {
        app.container.photos.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun realJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun registerPhoto(n: Int): Uri = Uri.parse("content://fotos/$n").also { uri ->
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(realJpeg()))
    }

    @Test
    fun notOnlineGivesNotActive() = runBlocking {
        val errors = Collections.synchronizedList(mutableListOf<Pair<String, PhotoException.Reason>>())
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { app.container.photos.errors.collect { errors += it } }
        // Motor parado: la foto se codifica pero no se envía.
        assertTrue(app.container.photos.enqueue("alguien", registerPhoto(1)))
        withTimeout(TIMEOUT) { while (errors.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("alguien" to PhotoException.Reason.NOT_ACTIVE), errors.toList())
        assertTrue(app.container.engine.conversations.value.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun onlineHandsThePhotoToTheEngine() {
        EngineHarness(1, network = app.network).use { peer ->
            assertTrue(runBlocking { app.container.start().await() })
            val (id, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            runBlocking { withTimeout(TIMEOUT) { app.container.engine.presence.first { it[id] == Presence.ONLINE } } }
            assertTrue(app.container.photos.enqueue(id, registerPhoto(2)))
            // Con el motor Online la foto llega al historial del motor (Delivered, o Failed si la sesión aún
            // no estaba lista: `presence` puede ir un instante por delante de la sesión).
            val item = runBlocking {
                withTimeout(TIMEOUT) { app.container.engine.conversations.first { all -> all[id].orEmpty().isNotEmpty() } }
            }[id]!!.single()
            assertTrue(item.content is ChatContent.Photo)
            assertEquals(Direction.OUT, item.direction)
            val jpeg = (item.content as ChatContent.Photo).jpeg
            assertEquals(0xD8.toByte(), jpeg[1])
            runBlocking { withTimeout(TIMEOUT) { app.container.photos.preparing.first { it.isEmpty() } } }
        }
    }

    @Test
    fun awaitPhotoFinishedWaitsWhileSending() = runBlocking {
        fun item(status: DeliveryStatus) = ChatItem("p1", Direction.OUT, ChatContent.Photo(ByteArray(4)), status, 0)
        val conversations = MutableStateFlow(mapOf("c" to listOf(item(DeliveryStatus.Sending(0, 4)))))
        // UNDISPATCHED: al volver de async, `first` ya evaluó el valor actual (sin esperas ni carreras).
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { awaitPhotoFinished(conversations, "c", "p1") }
        assertFalse(waiting.isCompleted)
        conversations.value = mapOf("c" to listOf(item(DeliveryStatus.Sending(2, 4))))
        assertFalse(waiting.isCompleted)
        conversations.value = mapOf("c" to listOf(item(DeliveryStatus.Delivered)))
        assertTrue(waiting.isCompleted)

        for (done in listOf(
            mapOf("c" to listOf(item(DeliveryStatus.Failed(FailReason.NOT_CONNECTED)))),
            mapOf("c" to emptyList()),
            emptyMap(),
        )) {
            val finished = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                awaitPhotoFinished(MutableStateFlow(done), "c", "p1")
            }
            assertTrue(finished.isCompleted)
        }
    }

    @Test
    fun stopClearsTheQueue() {
        val release = CountDownLatch(1)
        (3..5).forEach { n ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(Uri.parse("content://fotos/$n")) {
                release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                ByteArrayInputStream(realJpeg()) as InputStream
            }
        }
        val photos = app.container.photos
        assertTrue(photos.enqueue("a", Uri.parse("content://fotos/3")))
        assertTrue(photos.enqueue("a", Uri.parse("content://fotos/4")))
        assertTrue(photos.enqueue("b", Uri.parse("content://fotos/5")))
        assertFalse(photos.enqueue("b", Uri.parse("content://fotos/6")))
        assertEquals(mapOf("a" to 2, "b" to 1), photos.preparing.value)
        app.container.stop()
        // clear() es inmediato: ni pendientes ni en curso, y vuelve a caber la capacidad completa.
        assertEquals(emptyMap<String, Int>(), photos.preparing.value)
        release.countDown()
        assertTrue(photos.enqueue("a", registerPhoto(7)))
        assertTrue(photos.enqueue("a", registerPhoto(8)))
        assertTrue(photos.enqueue("a", registerPhoto(9)))
        runBlocking { withTimeout(TIMEOUT) { photos.preparing.first { it.isEmpty() } } }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
