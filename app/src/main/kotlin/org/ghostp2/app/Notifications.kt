package org.ghostp2.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.ghostp2.engine.Notice

/**
 * Notificaciones de GhostP2. Ninguna lleva contenido de mensajes: como mucho el nombre del contacto,
 * y solo en la versión privada (en la pantalla de bloqueo se ve la versión pública, sin nombre).
 */
object Notifications {
    /** Canal de la notificación fija del servicio (importancia baja: sin sonido). */
    const val CHANNEL_STATUS = "ghostp2_status"

    /** Canal de mensajes y avisos de contactos (importancia alta). */
    const val CHANNEL_MESSAGES = "ghostp2_messages"

    /** Id de la notificación fija del servicio en primer plano. */
    const val STATUS_ID = 1

    // Ids por tipo; la etiqueta es el id del contacto (una notificación por contacto y tipo).
    private const val MESSAGE_ID = 10
    private const val UNPAIRED_ID = 11
    private const val IDENTITY_CHANGED_ID = 12

    /** Notificación lista para publicar con `notify(tag, id, notification)`. */
    class Posted(val tag: String, val id: Int, val notification: Notification)

    /** Crea (o actualiza) los dos canales; idempotente. */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.channel_status_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_status_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.channel_messages_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_messages_description)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    /** Notificación fija del servicio: "GhostP2 activo · cifrado" con la acción "Desconectar". */
    fun status(context: Context): Notification {
        createChannels(context)
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            GhostService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ghost)
            .setContentTitle(context.getString(R.string.notification_status_title))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_action_disconnect), stop)
            .build()
    }

    /** "Nuevo mensaje de <nombre>", sin texto del mensaje; versión pública "Nuevo mensaje". */
    fun message(context: Context, contactName: String): Notification =
        contactNotification(
            context,
            title = context.getString(R.string.notification_message_title, contactName),
            text = null,
            publicTitle = context.getString(R.string.notification_message_public),
            category = NotificationCompat.CATEGORY_MESSAGE,
        )

    /** "<nombre> te ha eliminado de sus contactos". */
    fun unpaired(context: Context, name: String): Notification =
        contactNotification(
            context,
            title = context.getString(R.string.notification_unpaired_title, name),
            text = null,
            publicTitle = context.getString(R.string.notification_contact_public),
            category = NotificationCompat.CATEGORY_STATUS,
        )

    /** La autenticación del contacto falló: hay que volver a emparejar. */
    fun identityChanged(context: Context, name: String): Notification =
        contactNotification(
            context,
            title = context.getString(R.string.notification_identity_changed_title, name),
            text = context.getString(R.string.notification_identity_changed_text),
            publicTitle = context.getString(R.string.notification_contact_public),
            category = NotificationCompat.CATEGORY_STATUS,
        )

    /**
     * Notificación para un aviso del motor, o `null` si el aviso es solo de diagnóstico
     * (`PublicationFailed`, `ClientAuthFailed`: los muestra la pantalla de diagnóstico, no molestan).
     * `contactName` es el nombre guardado del contacto (o `null` si ya no se conoce).
     */
    fun forNotice(context: Context, notice: Notice, contactName: String?): Posted? {
        val fallback = context.getString(R.string.notification_unknown_contact)
        return when (notice) {
            is Notice.MessageArrived ->
                Posted(notice.contactId, MESSAGE_ID, message(context, contactName ?: fallback))
            is Notice.ContactUnpairedYou ->
                Posted(notice.contactId, UNPAIRED_ID, unpaired(context, notice.name))
            is Notice.IdentityChanged ->
                Posted(notice.contactId, IDENTITY_CHANGED_ID, identityChanged(context, contactName ?: fallback))
            Notice.PublicationFailed, is Notice.ClientAuthFailed -> null
        }
    }

    /** Publica [posted] si el usuario permite notificaciones (en API 33+ hace falta `POST_NOTIFICATIONS`). */
    fun post(context: Context, posted: Posted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(posted.tag, posted.id, posted.notification)
    }

    private fun contactNotification(
        context: Context,
        title: String,
        text: String?,
        publicTitle: String,
        category: String,
    ): Notification {
        createChannels(context)
        val public = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_ghost)
            .setContentTitle(publicTitle)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_ghost)
            .setContentTitle(title)
            .apply { text?.let(::setContentText) }
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_STOP = 1
    private const val REQUEST_OPEN = 2
}
