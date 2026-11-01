package org.ghostp2.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.ghostp2.app.AppContainer
import org.ghostp2.app.TestGhostApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class ViewModelsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication() as TestGhostApp

    class ProbeViewModel(val container: AppContainer, val id: String, val item: String) : ViewModel()

    @Test
    fun routeIdsRoundTripAndViewModelGetsTheContainer() {
        // Caracteres que hay que codificar en una ruta: '/', '%', espacio, '?', '#'.
        val id = "a/b 50%?#"
        val item = "x/y"
        var created: ProbeViewModel? = null
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "inicio") {
                composable("inicio") { LaunchedEffect(Unit) { nav.navigate(Routes.photo(id, item)) } }
                composable(Routes.PHOTO) { entry ->
                    val vm = ghostViewModel(app.container) { ProbeViewModel(it, entry.idArg(), entry.idArg("item")) }
                    created = vm
                    Text(vm.id)
                }
            }
        }
        compose.waitUntil(5_000) { created != null }
        val vm = created!!
        assertSame(app.container, vm.container)
        assertEquals(id, vm.id)
        assertEquals(item, vm.item)
    }
}
