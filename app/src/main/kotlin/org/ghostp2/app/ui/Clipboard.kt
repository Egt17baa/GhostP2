package org.ghostp2.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import org.ghostp2.app.R

/**
 * Copia `text` al portapapeles marcado como sensible: en Android 13+ (API 33) el sistema no lo muestra
 * en la vista previa del portapapeles.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

/**
 * Vacía el portapapeles si lo que tiene lo copió GhostP2 (una invitación: con su etiqueta, o empieza por
 * `ghostp2:` y está marcado como sensible). Lo ajeno no se toca. Mejor esfuerzo: en segundo plano el
 * sistema puede negar el acceso (lectura nula) o lanzar.
 */
fun clearOwnClip(context: Context) {
    try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val description = clipboard.primaryClipDescription ?: return
        val ownLabel = description.label?.toString() == context.getString(R.string.add_clip_label)
        val sensitive = description.extras?.getBoolean(EXTRA_IS_SENSITIVE_ANY_API) == true
        val invitation = sensitive && clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            ?.trim()?.startsWith(INVITATION_SCHEME, ignoreCase = true) == true
        if (!ownLabel && !invitation) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    } catch (_: Exception) {
        // Sin acceso al portapapeles: no se puede comprobar ni vaciar.
    }
}

private const val INVITATION_SCHEME = "ghostp2:"

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33) con su valor literal, que se lee igual en cualquier versión. */
private const val EXTRA_IS_SENSITIVE_ANY_API = "android.content.extra.IS_SENSITIVE"
