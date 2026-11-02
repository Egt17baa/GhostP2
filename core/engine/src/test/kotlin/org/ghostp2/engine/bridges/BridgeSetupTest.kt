package org.ghostp2.engine.bridges

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BridgeSetupTest {
    private val obfs4 = "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
        "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0"
    private val webtunnel = "webtunnel [2001:db8::5]:443 4F1C6A0D1B2E3F405162738495A6B7C8D9E0F1A2 " +
        "url=https://cover.example.org/aB3dE5 ver=0.0.1"

    @Test
    fun noneIsNull() {
        assertNull(bridgeSetupFor(BridgeMode.NONE, emptyList()))
        assertNull(bridgeSetupFor(BridgeMode.NONE, listOf(obfs4)), "las líneas guardadas no cuentan con «No»")
    }

    @Test
    fun obfs4UsesBuiltIn() {
        val setup = bridgeSetupFor(BridgeMode.OBFS4, listOf(webtunnel))
        assertEquals(BridgeSetup(BuiltInBridges.OBFS4), setup)
        assertEquals(setOf(PtKind.OBFS4), setup?.kinds)
    }

    @Test
    fun snowflakeUsesBuiltIn() {
        val setup = bridgeSetupFor(BridgeMode.SNOWFLAKE, listOf(obfs4))
        assertEquals(BridgeSetup(BuiltInBridges.SNOWFLAKE), setup)
        assertEquals(setOf(PtKind.SNOWFLAKE), setup?.kinds)
    }

    @Test
    fun customMixesKinds() {
        val setup = bridgeSetupFor(BridgeMode.CUSTOM, listOf("Bridge $obfs4", webtunnel, "  $obfs4 "))
        assertEquals(
            BridgeSetup(listOf(BridgeLine(PtKind.OBFS4, obfs4), BridgeLine(PtKind.WEBTUNNEL, webtunnel))),
            setup,
        )
        assertEquals(setOf(PtKind.OBFS4, PtKind.WEBTUNNEL), setup?.kinds)
        assertEquals(setOf(PtKind.OBFS4), bridgeSetupFor(BridgeMode.CUSTOM, listOf(obfs4))?.kinds)
    }

    @Test
    fun customWithoutLinesIsEmptySetup() {
        // Falla cerrado: «Mis puentes» sin líneas válidas nunca es «sin puentes» (null), sino un arranque por puente
        // sin puentes, que el transporte convierte en error de puente sin llegar a arrancar Tor.
        val empty = BridgeSetup(emptyList())
        assertEquals(empty, bridgeSetupFor(BridgeMode.CUSTOM, emptyList()))
        assertEquals(empty, bridgeSetupFor(BridgeMode.CUSTOM, listOf("", "  ")))
        assertEquals(empty, bridgeSetupFor(BridgeMode.CUSTOM, listOf(obfs4, "obfs4 roto")))
        assertEquals(emptySet<PtKind>(), bridgeSetupFor(BridgeMode.CUSTOM, emptyList())?.kinds)
    }
}
