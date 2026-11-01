package org.ghostp2.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.app.photo.PhotoBitmaps
import org.ghostp2.app.photo.PhotoSaverTest
import org.ghostp2.engine.ChatContent
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.Direction
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.Presence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatPhotoTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun text(id: Int) = app.getString(id)

    /** Bytes con cabecera JPEG; el decodificador de prueba no los mira. */
    private fun jpegLike() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private fun realJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private val testBitmaps = PhotoBitmaps(decode = { _, _ -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) })

    private var state by mutableStateOf<ChatUiState?>(null)
    private var preparing by mutableStateOf(0)
    private var photoError by mutableStateOf<PhotoError?>(null)
    private val opened = mutableListOf<String>()
    private var errorsShown = 0

    private fun show() = compose.setContent {
        ChatScreen(
            state = state,
            draft = "",
            onDraftChange = {},
            onSend = {},
            onBack = {},
            onOpenContact = {},
            onClear = {},
            photoContent = { item -> PhotoContent(item, testBitmaps, onOpen = { opened += item.id }) },
            preparingPhotos = preparing,
            photoError = photoError,
            onPhotoErrorShown = {
                errorsShown++
                photoError = null
            },
        )
    }

    private fun chat(vararg items: ChatItem) =
        ChatUiState(name = "Ana", presence = Presence.ONLINE, engineOnline = true, identityChanged = false, items = items.toList())

    @Test
    fun discardedPhotoShowsPlaceholder() {
        state = chat(
            ChatItem("descartada", Direction.IN, ChatContent.PhotoDiscarded(1000), DeliveryStatus.Received, 0),
            // Puesta a cero por el motor (aún sin pasar a PhotoDiscarded): tampoco se ve nada.
            ChatItem("a-cero", Direction.IN, ChatContent.Photo(ByteArray(5)), DeliveryStatus.Received, 1),
            ChatItem("viva", Direction.IN, ChatContent.Photo(jpegLike()), DeliveryStatus.Received, 2),
        )
        show()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasContentDescription(text(R.string.chat_photo))).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithText(text(R.string.chat_photo_discarded)).assertCountEquals(2)
        // La descartada no abre el visor; la viva, sí.
        compose.onAllNodesWithText(text(R.string.chat_photo_discarded))[0].performClick()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithContentDescription(text(R.string.chat_photo)).performClick()
        assertEquals(listOf("viva"), opened)
    }

    @Test
    fun sendingPhotoShowsProgress() {
        state = chat(ChatItem("p", Direction.OUT, ChatContent.Photo(jpegLike()), DeliveryStatus.Sending(25, 100), 0))
        show()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasContentDescription(text(R.string.chat_photo))).fetchSemanticsNodes().isNotEmpty() }
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo(0.25f, 0f..1f), progress)
        compose.onNodeWithText(text(R.string.chat_status_sending)).assertExists()
    }

    @Test
    fun fullQueueShowsWaitMessage() {
        state = chat()
        show()
        compose.onAllNodesWithText(text(R.string.chat_photo_queue_full)).assertCountEquals(0)
        photoError = PhotoError.QUEUE_FULL
        compose.onNodeWithText(text(R.string.chat_photo_queue_full)).assertExists()
        // Se confirma una sola vez (no se repite al recomponer).
        assertEquals(1, errorsShown)
        assertNull(photoError)
    }

    @Test
    fun preparingPhotoShowsNotice() {
        state = chat()
        show()
        compose.onAllNodesWithText(text(R.string.chat_photo_preparing)).assertCountEquals(0)
        preparing = 2
        compose.onNodeWithText(text(R.string.chat_photo_preparing)).assertExists()
    }

    @Test
    fun viewerShowsPhotoAndSave() {
        var photo by mutableStateOf<ViewerPhoto>(ViewerPhoto.Loading)
        compose.setContent {
            PhotoViewerScreen(photo = photo, saveResult = null, onBack = {}, onSaveTo = {}, onSaveResultShown = {})
        }
        photo = ViewerPhoto.Ready(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.asImageBitmap())
        compose.onNodeWithText(text(R.string.photo_save)).assertExists()
        photo = ViewerPhoto.Discarded
        compose.onNodeWithText(text(R.string.chat_photo_discarded)).assertExists()
        compose.onAllNodesWithText(text(R.string.photo_save)).assertCountEquals(0)
        photo = ViewerPhoto.Gone
        compose.onNodeWithText(text(R.string.photo_gone)).assertExists()
        compose.onAllNodesWithText(text(R.string.photo_save)).assertCountEquals(0)
    }

    @Test
    fun saveFileNameFollowsPattern() {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse("2026-11-01 09:05:07")!!
        assertEquals("GhostP2-20261101-090507.jpg", saveFileName(date))
    }

    // --- ViewModel con el motor real y la cola real ---

    private fun viewModel(id: String): ChatViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(app.container, id) } })[ChatViewModel::class]

    private fun pairedOnline(peer: EngineHarness): String {
        assertTrue(runBlocking { app.container.start().await() })
        val (id, _) = runBlocking {
            peer.startAll()
            pairWithPeer(app, peer, TIMEOUT)
        }
        runBlocking { withTimeout(TIMEOUT) { app.container.engine.presence.first { it[id] == Presence.ONLINE } } }
        return id
    }

    @Test
    fun fullQueueIsReportedByTheViewModel() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.engineOnline == true }
            // Lecturas bloqueadas: la primera foto se queda codificándose y las otras dos esperando.
            val resolver = app.contentResolver
            (1..4).forEach { n ->
                shadowOf(resolver).registerInputStreamSupplier(Uri.parse("content://fotos/$n")) {
                    release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    ByteArrayInputStream(ByteArray(10)) as InputStream
                }
            }
            assertTrue(vm.pickPhoto(Uri.parse("content://fotos/1")))
            assertTrue(vm.pickPhoto(Uri.parse("content://fotos/2")))
            assertTrue(vm.pickPhoto(Uri.parse("content://fotos/3")))
            assertNull(vm.photoErrors.value)
            eventually(TIMEOUT) { vm.preparingPhotos.value == 3 }
            assertFalse(vm.pickPhoto(Uri.parse("content://fotos/4")))
            assertEquals(PhotoError.QUEUE_FULL, vm.photoErrors.value)
            vm.photoErrorShown()
            assertNull(vm.photoErrors.value)
            // Al soltarlas, no son imágenes: se informa de que no se pudieron leer.
            release.countDown()
            eventually(TIMEOUT) { vm.photoErrors.value == PhotoError.UNREADABLE }
            eventually(TIMEOUT) { vm.preparingPhotos.value == 0 }
        }
    }

    @Test
    fun stoppedEngineDoesNotQueue() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.engineOnline == true }
            runBlocking { app.container.engine.stop() }
            eventually(TIMEOUT) { vm.state.value?.engineOnline == false }
            assertTrue(vm.pickPhoto(Uri.parse("content://fotos/9")))
            assertEquals(PhotoError.NOT_ACTIVE, vm.photoErrors.value)
            assertEquals(0, vm.preparingPhotos.value)
        }
    }

    @Test
    fun bitmapsKeepOnlyLivePhotos() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.state.value?.engineOnline == true }
            // Un id que no está en la conversación no se guarda en caché (se devuelve, pero no se retiene).
            assertTrue(vm.bitmaps.thumbnail("ajena", realJpeg()) != null)
            assertTrue("ajena" !in vm.bitmaps.cachedIds())
            // Una foto viva sí; al borrar el chat se suelta.
            val photoId = app.container.engine.sendPhoto(id, realJpeg())
            eventually(TIMEOUT) { vm.state.value?.items?.any { it.id == photoId } == true }
            val live = vm.state.value!!.items.single { it.id == photoId }.content as ChatContent.Photo
            assertTrue(vm.bitmaps.thumbnail(photoId, live.jpeg) != null)
            assertTrue(photoId in vm.bitmaps.cachedIds())
            vm.clear()
            eventually(TIMEOUT) { photoId !in vm.bitmaps.cachedIds() }
        }
    }

    @Test
    fun viewerSaveOfGonePhotoDeletesDocument() {
        val provider = Robolectric.buildContentProvider(PhotoSaverTest.RecordingProvider::class.java).create("org.ghostp2.pruebas.visor").get()
        val target = DocumentsContract.buildDocumentUri("org.ghostp2.pruebas.visor", "foto")
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { PhotoViewerViewModel(app.container, "nadie", "nada") } })[PhotoViewerViewModel::class]
        eventually(TIMEOUT) { vm.photo.value == ViewerPhoto.Gone }
        vm.save(app.contentResolver, target)
        eventually(TIMEOUT) { vm.saveResult.value == SaveResult.FAILED }
        assertEquals(listOf(target), provider.deleted)
    }

    @Test
    fun viewerErasesImageWhenPhotoGoes() {
        EngineHarness(1, network = app.network).use { peer ->
            val id = pairedOnline(peer)
            val photoId = app.container.engine.sendPhoto(id, realJpeg())
            val vm = ViewModelProvider.create(store, viewModelFactory { initializer { PhotoViewerViewModel(app.container, id, photoId) } })[PhotoViewerViewModel::class]
            eventually(TIMEOUT) { vm.photo.value is ViewerPhoto.Ready }
            val bitmap = (vm.photo.value as ViewerPhoto.Ready).image.asAndroidBitmap()
            assertTrue(bitmap.getPixel(0, 0) != 0)
            app.container.engine.clearConversation(id)
            eventually(TIMEOUT) { vm.photo.value == ViewerPhoto.Gone }
            eventually(TIMEOUT) { bitmap.getPixel(0, 0) == 0 }
            assertFalse(bitmap.isRecycled)
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
