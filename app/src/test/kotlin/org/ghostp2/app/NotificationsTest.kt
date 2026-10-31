package org.ghostp2.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.ghostp2.engine.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class NotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** Todos los textos visibles de una notificación (título, texto, subtítulos, ticker…). */
    private fun Notification.visibleTexts(): List<String> = listOfNotNull(
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        tickerText,
    ).map { it.toString() }

    @Test
    fun statusNotificationIsOngoingWithStopAction() {
        val n = Notifications.status(context)

        assertEquals(Notifications.CHANNEL_STATUS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("GhostP2 activo · cifrado", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, n.actions.size)
        assertEquals("Desconectar", n.actions[0].title.toString())
        val pending = shadowOf(n.actions[0].actionIntent)
        assertTrue(pending.isService)
        val intent = pending.savedIntent
        assertEquals(GhostService.ACTION_STOP, intent.action)
        assertEquals(GhostService::class.java.name, intent.component?.className)
    }

    @Test
    fun errorStatusNotificationKeepsStopAction() {
        val n = Notifications.status(context, error = true)

        assertEquals("GhostP2 sin conexión · error", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(n.visibleTexts().any { it.contains("activo") })
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(GhostService.ACTION_STOP, shadowOf(n.actions.single().actionIntent).savedIntent.action)
    }

    @Test
    fun messageNotificationHasNoMessageText() {
        val n = Notifications.message(context, "Ana")

        assertEquals(Notifications.CHANNEL_MESSAGES, n.channelId)
        // Solo el nombre del contacto: ningún otro texto que pudiera llevar el mensaje.
        assertEquals(listOf("Nuevo mensaje de Ana"), n.visibleTexts())
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        val public = n.publicVersion
        assertEquals(listOf("Nuevo mensaje"), public.visibleTexts())
        assertFalse(public.visibleTexts().any { it.contains("Ana") })
    }

    @Test
    fun messageNoticeBecomesNameOnlyNotificationPerContact() {
        val posted = Notifications.forNotice(context, Notice.MessageArrived("c1"), contactName = "Ana")

        assertNotNull(posted)
        posted!!
        assertEquals("c1", posted.tag)
        assertEquals(listOf("Nuevo mensaje de Ana"), posted.notification.visibleTexts())
        // Diagnóstico interno: sin notificación.
        assertNull(Notifications.forNotice(context, Notice.PublicationFailed, contactName = null))
    }

    @Test
    fun unpairedAndIdentityChangedArePrivate() {
        val unpaired = Notifications.unpaired(context, "Ana")
        val changed = Notifications.identityChanged(context, "Ana")

        assertTrue(unpaired.visibleTexts().contains("Ana te ha eliminado de sus contactos"))
        for (n in listOf(unpaired, changed)) {
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertFalse(n.publicVersion.visibleTexts().any { it.contains("Ana") })
        }
    }

    @Test
    fun channelsHaveExpectedImportance() {
        Notifications.createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(Notifications.CHANNEL_STATUS).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(Notifications.CHANNEL_MESSAGES).importance)
    }
}
