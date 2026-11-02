package org.ghostp2.engine.bridges

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeLineTest {
    private val fpA = "D9A82D2F9C2F65A18407B1D2B764F130847F8B5D"
    private val certA = "bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg"
    private val a = "obfs4 37.218.245.14:38224 $fpA cert=$certA iat-mode=0"
    private val b = "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 " +
        "cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0"
    private val v6 = "obfs4 [2001:db8::1]:443 $fpA cert=$certA iat-mode=2"
    private val wt = "webtunnel [2001:db8:3a5c:8fb6:57e9:e67f:6a5b:f5d3]:443 " +
        "4F1C6A0D1B2E3F405162738495A6B7C8D9E0F1A2 url=https://cover.example.org/aB3dE5 ver=0.0.1"
    private val wtNoVer = "webtunnel 192.0.2.7:8443 4F1C6A0D1B2E3F405162738495A6B7C8D9E0F1A2 url=https://cover.example.org/x"

    private fun obfs4(line: String) = BridgeLine(PtKind.OBFS4, line)

    private fun ok(vararg lines: BridgeLine) = ParseResult.Ok(lines.toList())

    /** Línea obfs4 válida distinta para cada `i`. */
    private fun distinct(i: Int) = "obfs4 192.0.2.1:${1000 + i} $fpA cert=$certA iat-mode=0"

    @Test
    fun parsesValidObfs4AndWebtunnel() {
        assertEquals(ok(obfs4(a)), BridgeLines.parse(a))
        assertEquals(ok(obfs4(a)), BridgeLines.parse("Bridge $a"))
        assertEquals(ok(obfs4(v6)), BridgeLines.parse(v6))
        assertEquals(ok(BridgeLine(PtKind.WEBTUNNEL, wt)), BridgeLines.parse("Bridge $wt"))
        assertEquals(ok(BridgeLine(PtKind.WEBTUNNEL, wtNoVer)), BridgeLines.parse(wtNoVer))
        assertEquals(
            ok(obfs4(a), obfs4(v6), BridgeLine(PtKind.WEBTUNNEL, wt)),
            BridgeLines.parse("$a\n$v6\n$wt"),
        )
        assertEquals("obfs4", PtKind.OBFS4.torName)
        assertEquals("webtunnel", PtKind.WEBTUNNEL.torName)
        assertEquals("snowflake", PtKind.SNOWFLAKE.torName)
        assertEquals(10, BridgeLines.MAX)
    }

    @Test
    fun parsesMessyPaste() {
        val dupMessy = "  obfs4   37.218.245.14:38224   ${fpA.lowercase()}  cert=$certA   iat-mode=0 "
        val text = "Bridge $a\r\n\r\n  $b  \n$dupMessy\n\t\n"
        assertEquals(ok(obfs4(a), obfs4(b)), BridgeLines.parse(text))
        assertEquals(ok(obfs4(a)), BridgeLines.parse("\r  bridge   $a\r"))
        assertEquals(ok(), BridgeLines.parse("  \n\r\n"))
    }

    @Test
    fun rejectsUnsupportedAndTooMany() {
        val meek = "meek_lite 192.0.2.18:80 BE776A53492E1E044A26F17306E1BC46A55A1625 " +
            "url=https://meek.example.net/ front=ajax.example.com"
        val snow = BuiltInBridges.SNOWFLAKE.first().line
        assertEquals(ParseResult.Invalid(listOf(1), false), BridgeLines.parse(meek))
        assertEquals(ParseResult.Invalid(listOf(2), false), BridgeLines.parse("$a\n$snow"))
        assertEquals(ParseResult.Invalid(listOf(2, 4), false), BridgeLines.parse("$a\n$meek\n\n$snow"))

        val ten = (1..10).joinToString("\n") { distinct(it) }
        assertTrue(BridgeLines.parse(ten) is ParseResult.Ok)
        assertTrue(BridgeLines.parse("$ten\n${distinct(1)}") is ParseResult.Ok, "un duplicado no cuenta")
        assertEquals(ParseResult.Invalid(emptyList(), true), BridgeLines.parse("$ten\n${distinct(11)}"))
        assertEquals(ParseResult.Invalid(listOf(12), true), BridgeLines.parse("$ten\n${distinct(11)}\n$meek"))

        val bad = listOf(
            "obfs4 37.218.245.14:70000 $fpA cert=$certA iat-mode=0",
            "obfs4 37.218.245.14:0 $fpA cert=$certA iat-mode=0",
            "obfs4 37.218.245.14 $fpA cert=$certA iat-mode=0",
            "obfs4 37.218.245.14:443 ${fpA.dropLast(1)} cert=$certA iat-mode=0",
            "obfs4 37.218.245.14:443 ${fpA.dropLast(1)}G cert=$certA iat-mode=0",
            "obfs4 37.218.245.256:443 $fpA cert=$certA iat-mode=0",
            "obfs4 bridge.example.org:443 $fpA cert=$certA iat-mode=0",
            "obfs4 [2001:db8::1::2]:443 $fpA cert=$certA iat-mode=0",
            "obfs4 2001:db8::1:443 $fpA cert=$certA iat-mode=0",
            "obfs4 37.218.245.14:443 $fpA cert=$certA iat-mode=3",
            "obfs4 37.218.245.14:443 $fpA cert=${certA.dropLast(2)}== iat-mode=0",
            "obfs4 37.218.245.14:443 $fpA cert=$certA",
            "obfs4 37.218.245.14:443 $fpA iat-mode=0 cert=$certA",
            "obfs4 37.218.245.14:443 $fpA cert=$certA iat-mode=0 extra=1",
            "webtunnel 192.0.2.7:443 $fpA url=http://cover.example.org/x",
            "webtunnel 192.0.2.7:443 $fpA url=https:///x",
            "webtunnel 192.0.2.7:443 $fpA",
            "webtunnel 192.0.2.7:443 $fpA url=https://cover.example.org/x ver=",
            "Bridge",
            "obfs4",
            "garbage",
        )
        for (line in bad) {
            assertEquals(ParseResult.Invalid(listOf(2), false), BridgeLines.parse("$a\n$line"), line)
        }
    }

    @Test
    fun parsesQrArray() {
        assertEquals(ok(obfs4(a), obfs4(b)), BridgeLines.parseQr("['$a', '$b']"))
        assertEquals(ok(obfs4(a), obfs4(b)), BridgeLines.parseQr(" [ \"$a\" ,\n\"$b\" ] "))
        assertEquals(ok(obfs4(a)), BridgeLines.parseQr("['$a', '$a']"))
        assertEquals(ok(obfs4(a), obfs4(b)), BridgeLines.parseQr("$a\n$b"), "texto plano")

        assertTrue(BridgeLines.parseQr("hola, mundo") is ParseResult.Invalid)
        assertTrue(BridgeLines.parseQr("") is ParseResult.Invalid)
        assertTrue(BridgeLines.parseQr("[]") is ParseResult.Invalid)
        assertTrue(BridgeLines.parseQr("['$a', ") is ParseResult.Invalid)
        assertEquals(ParseResult.Invalid(listOf(2), false), BridgeLines.parseQr("['$a', 'nope']"))
        val eleven = (1..11).joinToString(", ") { "'${distinct(it)}'" }
        assertEquals(ParseResult.Invalid(emptyList(), true), BridgeLines.parseQr("[$eleven]"))
    }

    @Test
    fun builtInListsParse() {
        assertEquals(7, BuiltInBridges.OBFS4.size)
        assertEquals(2, BuiltInBridges.SNOWFLAKE.size)
        assertTrue(BuiltInBridges.OBFS4.all { it.kind == PtKind.OBFS4 })
        assertTrue(BuiltInBridges.SNOWFLAKE.all { it.kind == PtKind.SNOWFLAKE })
        for (line in BuiltInBridges.OBFS4) {
            assertEquals(ok(line), BridgeLines.parse(line.line), line.line)
        }
        assertEquals(ParseResult.Ok(BuiltInBridges.OBFS4), BridgeLines.parse(BuiltInBridges.OBFS4.joinToString("\n") { it.line }))

        assertFalse(BuiltInBridges.SNOWFLAKE_ICE.contains("google", ignoreCase = true))
        assertEquals("https://1098762253.rsc.cdn77.org/", BuiltInBridges.SNOWFLAKE_BROKER)
        assertEquals("app.datapacket.com,www.datapacket.com", BuiltInBridges.SNOWFLAKE_FRONTS)
        assertEquals(8, BuiltInBridges.SNOWFLAKE_ICE.split(',').size)
        assertTrue(BuiltInBridges.SNOWFLAKE_ICE.split(',').all { it.startsWith("stun:") && it.endsWith(":3478") })
        for (line in BuiltInBridges.SNOWFLAKE) {
            assertTrue(line.line.startsWith("snowflake "), line.line)
            assertTrue(line.line.contains(" url=${BuiltInBridges.SNOWFLAKE_BROKER} "), line.line)
            assertTrue(line.line.contains(" fronts=${BuiltInBridges.SNOWFLAKE_FRONTS} "), line.line)
            assertTrue(line.line.contains(" ice=${BuiltInBridges.SNOWFLAKE_ICE} "), line.line)
        }
        for (line in BuiltInBridges.OBFS4 + BuiltInBridges.SNOWFLAKE) {
            assertFalse(line.line.contains("ampcache", ignoreCase = true), line.line)
            assertFalse(line.line.contains("ampproject", ignoreCase = true), line.line)
            assertFalse(line.line.contains("google", ignoreCase = true), line.line)
            assertFalse(line.line.startsWith("Bridge"), line.line)
        }
    }
}
