package org.ghostp2.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.ghostp2.engine.bridges.BridgeMode
import org.ghostp2.engine.bridges.BridgeSetup
import org.ghostp2.engine.bridges.BuiltInBridges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** El ajuste «Puentes» que recibe Tor en cada arranque (R30), como lo conecta `AppContainer`. */
class BridgeSupplierTest {
    private val settings = InMemorySettingsRepository()
    private val supplier = bridgeSupplier(settings)

    @Test
    fun readsTheCurrentStoredSettingOnEveryStart() = runBlocking {
        assertNull(supplier())

        settings.update { it.copy(bridgeMode = BridgeMode.OBFS4) }
        assertEquals(BridgeSetup(BuiltInBridges.OBFS4), supplier())

        settings.update { it.copy(bridgeMode = BridgeMode.SNOWFLAKE) }
        assertEquals(BridgeSetup(BuiltInBridges.SNOWFLAKE), supplier())

        val mine = BuiltInBridges.OBFS4.takeLast(2)
        settings.update { it.copy(bridgeMode = BridgeMode.CUSTOM, customBridges = mine.map { b -> b.line }) }
        assertEquals(BridgeSetup(mine), supplier())

        // "Mis puentes" sin líneas válidas: un BridgeSetup vacío (falla cerrado), nunca null (directo).
        settings.update { it.copy(customBridges = listOf("obfs4 basura")) }
        assertEquals(BridgeSetup(emptyList()), supplier())

        settings.update { it.copy(bridgeMode = BridgeMode.NONE) }
        assertNull(supplier())
    }

    @Test
    fun readFailuresAreThrownNotTurnedIntoNoBridges() = runBlocking {
        settings.update { it.copy(bridgeMode = BridgeMode.OBFS4) }
        val failure = IOException("ilegible")
        settings.getFailure = failure
        try {
            supplier()
            fail("Debía lanzar")
        } catch (e: IOException) {
            assertSame(failure, e)
        }

        val cancelled = CancellationException("cancelado")
        settings.getFailure = cancelled
        try {
            supplier()
            fail("Debía lanzar")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
    }
}
