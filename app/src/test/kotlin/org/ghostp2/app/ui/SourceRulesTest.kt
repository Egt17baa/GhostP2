package org.ghostp2.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reglas sobre el código fuente de la app (restricciones globales del plan 3). */
class SourceRulesTest {
    /** Fuentes Kotlin de `app/src/main/kotlin` (Gradle ejecuta las pruebas desde el directorio del módulo). */
    private fun sources(): List<File> {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue("No se encontraron fuentes", it.isNotEmpty()) }
    }

    @Test
    fun allTextFieldsAreGhostTextField() {
        // Cualquier `…TextField(` (TextField, OutlinedTextField, BasicTextField, SecureTextField,
        // OutlinedSecureTextField, BasicSecureTextField…) salvo GhostTextField.
        val pattern = Regex("""\b(?!GhostTextField\()\w*TextField\(""")
        val offenders = sources()
            .filter { it.name != "GhostTextField.kt" }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.path }
        assertTrue("Campos de texto que no son GhostTextField: $offenders", offenders.isEmpty())
    }

    @Test
    fun textFieldPatternCatchesEveryVariant() {
        val pattern = Regex("""\b(?!GhostTextField\()\w*TextField\(""")
        listOf("TextField(", "OutlinedTextField(", "BasicTextField(", "SecureTextField(", "OutlinedSecureTextField(")
            .forEach { assertTrue(it, pattern.containsMatchIn(" $it")) }
        assertTrue(!pattern.containsMatchIn(" GhostTextField(value"))
    }

    @Test
    fun noTextFieldStateInSources() {
        // El estado de texto de Compose se puede guardar en el Bundle: los campos usan un String en memoria.
        val offenders = sources().filter { Regex("""TextFieldState\(""").containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("TextFieldState en las fuentes: $offenders", offenders.isEmpty())
    }

    @Test
    fun noSaveableStateInSources() {
        val pattern = Regex("""rememberSaveable|SavedStateHandle|rememberTextFieldState""")
        val offenders = sources().filter { pattern.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("Estado guardable (lo escribe el sistema en disco): $offenders", offenders.isEmpty())
    }
}
