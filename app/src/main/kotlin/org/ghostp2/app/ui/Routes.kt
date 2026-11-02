package org.ghostp2.app.ui

import android.net.Uri

/**
 * Rutas del `NavHost`. Solo llevan ids de contacto o de elemento (el estado de navegación lo guarda el
 * sistema en disco): nunca nombres, mensajes ni nada más.
 */
object Routes {
    const val CONTACTS = "contactos"
    const val ADD = "anadir"
    const val NAME = "nombrar/{id}"
    const val CONTACT = "contacto/{id}"
    const val CHAT = "chat/{id}"
    const val PHOTO = "foto/{id}/{item}"
    const val SETTINGS = "ajustes"
    const val BRIDGES = "puentes"
    const val LICENSES = "licencias"

    fun name(id: String) = "nombrar/${Uri.encode(id)}"

    fun contact(id: String) = "contacto/${Uri.encode(id)}"

    fun chat(id: String) = "chat/${Uri.encode(id)}"

    fun photo(id: String, item: String) = "foto/${Uri.encode(id)}/${Uri.encode(item)}"
}
