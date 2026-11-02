package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.config.TorConfig
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.config.TorSetting
import org.ghostp2.engine.bridges.BridgeLine
import org.ghostp2.engine.bridges.BridgeSetup
import org.ghostp2.engine.bridges.BuiltInBridges
import org.ghostp2.engine.bridges.PtKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Configuración de Tor con puentes (R30), generada en la JVM sin un Tor real. */
class BridgeConfigTest {
    private val obfs4 = BuiltInBridges.OBFS4.first()
    private val webtunnel = BridgeLine(
        PtKind.WEBTUNNEL,
        "webtunnel [2001:db8::5]:443 4F1C6A0D1B2E3F405162738495A6B7C8D9E0F1A2 url=https://cover.example.org/aB3dE5 ver=0.0.1",
    )

    private fun TorConfig.items(option: TorOption): List<TorSetting.LineItem> =
        flatMap { it.items }.filter { it.option == option }

    /** Las líneas tal como kmp-tor las manda a Tor en `LOADCONF` (`opción argumento`). */
    private fun TorConfig.lines(): List<String> = toString().lines()

    @Test
    fun obfs4ConfigLines() {
        val config = TorConfig.Builder {
            applyGhostConfig()
            applyBridgeConfig(BridgeSetup(BuiltInBridges.OBFS4), mapOf(PtKind.OBFS4 to 4711))
        }
        assertEquals(listOf("1"), config.items(TorOption.UseBridges).map { it.argument })
        assertEquals(BuiltInBridges.OBFS4.map { it.line }, config.items(TorOption.Bridge).map { it.argument })
        assertEquals(
            listOf("obfs4 socks5 127.0.0.1:4711"),
            config.items(TorOption.ClientTransportPlugin).map { it.argument },
        )
        val lines = config.lines()
        assertTrue("UseBridges 1" in lines)
        assertEquals(BuiltInBridges.OBFS4.map { "Bridge ${it.line}" }, lines.filter { it.startsWith("Bridge ") })
        assertTrue("ClientTransportPlugin obfs4 socks5 127.0.0.1:4711" in lines)
        // La configuración propia sigue igual: SOCKS solo .onion en 127.0.0.1, sin DNSPort ni HTTPTunnelPort.
        assertEquals("auto", config.items(TorOption.__SocksPort).single().argument)
        assertEquals("0", config.items(TorOption.DNSPort).single().argument)
        assertEquals("0", config.items(TorOption.HTTPTunnelPort).single().argument)
    }

    @Test
    fun mixedCustomConfig() {
        val setup = BridgeSetup(listOf(obfs4, webtunnel))
        val config = TorConfig.Builder {
            applyBridgeConfig(setup, mapOf(PtKind.OBFS4 to 4711, PtKind.WEBTUNNEL to 4712, PtKind.SNOWFLAKE to 4713))
        }
        assertEquals(listOf(obfs4.line, webtunnel.line), config.items(TorOption.Bridge).map { it.argument })
        assertEquals(
            listOf("obfs4 socks5 127.0.0.1:4711", "webtunnel socks5 127.0.0.1:4712"),
            config.items(TorOption.ClientTransportPlugin).map { it.argument },
        )
        assertEquals(1, config.items(TorOption.UseBridges).size)

        val snowflake = TorConfig.Builder {
            applyBridgeConfig(BridgeSetup(BuiltInBridges.SNOWFLAKE), mapOf(PtKind.SNOWFLAKE to 4713))
        }
        assertEquals(BuiltInBridges.SNOWFLAKE.map { it.line }, snowflake.items(TorOption.Bridge).map { it.argument })
        assertEquals(
            listOf("snowflake socks5 127.0.0.1:4713"),
            snowflake.items(TorOption.ClientTransportPlugin).map { it.argument },
        )
    }

    @Test
    fun noSetupAddsNothing() {
        val config = TorConfig.Builder {
            applyGhostConfig()
            putAll(emptyList<TorSetting>()) // lo que recibe el driver con «No» (ver KmpTorDriver)
        }
        for (option in listOf(TorOption.UseBridges, TorOption.Bridge, TorOption.ClientTransportPlugin)) {
            assertTrue("$option no debe configurarse sin puentes", config.items(option).isEmpty())
        }
        assertEquals(TorConfig.Builder { applyGhostConfig() }, config)
        assertEquals(TorConfig.Builder { applyGhostConfig() }.toString(), config.toString())
    }

    @Test
    fun refusesIncompleteBridgeConfig() {
        // Falla cerrado: sin puentes, sin puerto para un tipo o con un puerto imposible no se genera nada.
        val cases = listOf(
            BridgeSetup(emptyList()) to mapOf(PtKind.OBFS4 to 4711),
            BridgeSetup(listOf(obfs4, webtunnel)) to mapOf(PtKind.OBFS4 to 4711),
            BridgeSetup(listOf(obfs4)) to mapOf(PtKind.OBFS4 to 0),
            BridgeSetup(listOf(obfs4)) to mapOf(PtKind.OBFS4 to 65_536),
        )
        for ((setup, ports) in cases) {
            try {
                bridgeSettings(setup, ports)
                fail("se esperaba IllegalArgumentException: $setup $ports")
            } catch (e: IllegalArgumentException) {
                // esperado
            }
        }
    }
}
