package org.ghostp2.engine.bridges

import java.net.URI
import java.net.URISyntaxException

/** Transporte disfrazado (pluggable transport) de IPtProxy; [torName] es su nombre en la configuración de Tor. */
enum class PtKind(val torName: String) { OBFS4("obfs4"), WEBTUNNEL("webtunnel"), SNOWFLAKE("snowflake") }

/** Una línea `Bridge` de Tor, sin el prefijo `Bridge` y normalizada (un espacio entre campos, huella en mayúsculas). */
data class BridgeLine(val kind: PtKind, val line: String)

sealed interface ParseResult {
    data class Ok(val lines: List<BridgeLine>) : ParseResult

    /** [lineNumbers]: líneas no válidas, desde 1; [tooMany]: más de [BridgeLines.MAX] líneas válidas distintas. */
    data class Invalid(val lineNumbers: List<Int>, val tooMany: Boolean) : ParseResult
}

/**
 * Validación de «Mis puentes» (R30): solo obfs4 y webtunnel. Acepta el prefijo `Bridge`, la huella en
 * minúsculas y espacios de más; quita las líneas en blanco y los duplicados. Nunca resuelve nombres.
 */
object BridgeLines {
    const val MAX = 10

    private val NEWLINE = Regex("\r\n|\r|\n")
    private val SPACES = Regex("\\s+")
    private val FINGERPRINT = Regex("[0-9A-Fa-f]{40}")

    /** Certificado obfs4: 52 bytes en base64 sin relleno. */
    private val CERT = Regex("cert=[A-Za-z0-9+/]{70}")
    private val IAT_MODE = Regex("iat-mode=[012]")
    private val VERSION = Regex("ver=[A-Za-z0-9._-]+")
    private val IPV4_OCTET = Regex("0|[1-9][0-9]{0,2}")
    private val PORT = Regex("[1-9][0-9]{0,4}")
    private val HEX_GROUP = Regex("[0-9A-Fa-f]{1,4}")

    /** Texto pegado, una línea por puente. Los números de [ParseResult.Invalid] son los de las líneas del texto. */
    fun parse(text: String): ParseResult = parseItems(text.split(NEWLINE))

    /**
     * Contenido de un QR de bridges.torproject.org: un array tipo `['obfs4 …', 'obfs4 …']`, o texto plano como en
     * [parse]. Un QR sin ningún puente no es válido.
     */
    fun parseQr(payload: String): ParseResult {
        val trimmed = payload.trim()
        val result = if (trimmed.startsWith("[")) {
            val items = qrItems(trimmed) ?: return ParseResult.Invalid(listOf(1), false)
            parseItems(items)
        } else {
            parse(payload)
        }
        return if (result is ParseResult.Ok && result.lines.isEmpty()) ParseResult.Invalid(listOf(1), false) else result
    }

    private fun parseItems(items: List<String>): ParseResult {
        val invalid = mutableListOf<Int>()
        val lines = LinkedHashSet<BridgeLine>()
        items.forEachIndexed { index, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val line = normalize(raw)
            if (line == null) invalid += index + 1 else lines += line
        }
        val tooMany = lines.size > MAX
        return if (invalid.isEmpty() && !tooMany) ParseResult.Ok(lines.toList()) else ParseResult.Invalid(invalid, tooMany)
    }

    /** Elementos entre comillas (simples o dobles) de un array `[…]`; `null` si no tiene esa forma. */
    private fun qrItems(text: String): List<String>? {
        val items = mutableListOf<String>()
        var i = 1
        fun skipSpaces() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
        skipSpaces()
        if (i < text.length && text[i] == ']') return if (i == text.length - 1) items else null
        while (true) {
            skipSpaces()
            if (i >= text.length) return null
            val quote = text[i]
            if (quote != '\'' && quote != '"') return null
            val end = text.indexOf(quote, i + 1)
            if (end < 0) return null
            items += text.substring(i + 1, end)
            i = end + 1
            skipSpaces()
            if (i >= text.length) return null
            when (text[i]) {
                ',' -> i++
                ']' -> return if (i == text.length - 1) items else null
                else -> return null
            }
        }
    }

    private fun normalize(raw: String): BridgeLine? {
        var tokens = raw.trim().split(SPACES)
        if (tokens.first().equals("Bridge", ignoreCase = true)) tokens = tokens.drop(1)
        if (tokens.size < 3) return null
        val address = normalizeAddress(tokens[1]) ?: return null
        if (!FINGERPRINT.matches(tokens[2])) return null
        val fingerprint = tokens[2].uppercase()
        val args = tokens.drop(3)
        val kind = when (tokens[0].lowercase()) {
            PtKind.OBFS4.torName -> PtKind.OBFS4.takeIf { obfs4Args(args) }
            PtKind.WEBTUNNEL.torName -> PtKind.WEBTUNNEL.takeIf { webtunnelArgs(args) }
            else -> null
        } ?: return null
        return BridgeLine(kind, (listOf(kind.torName, address, fingerprint) + args).joinToString(" "))
    }

    private fun obfs4Args(args: List<String>): Boolean =
        args.size == 2 && CERT.matches(args[0]) && IAT_MODE.matches(args[1])

    private fun webtunnelArgs(args: List<String>): Boolean =
        (args.size == 1 || (args.size == 2 && VERSION.matches(args[1]))) &&
            args[0].startsWith("url=") && isHttpsUrl(args[0].removePrefix("url="))

    private fun isHttpsUrl(value: String): Boolean {
        if (!value.startsWith("https://")) return false
        return try {
            !URI(value).host.isNullOrEmpty()
        } catch (_: URISyntaxException) {
            false
        }
    }

    /** `IPv4:puerto` o `[IPv6]:puerto` normalizado; `null` si no es válido (nunca un nombre de host). */
    private fun normalizeAddress(value: String): String? {
        val host: String
        val port: String
        if (value.startsWith("[")) {
            val close = value.indexOf("]:")
            if (close < 0) return null
            host = value.substring(1, close)
            port = value.substring(close + 2)
            if (!isIpv6(host)) return null
        } else {
            val parts = value.split(':')
            if (parts.size != 2) return null
            host = parts[0]
            port = parts[1]
            if (!isIpv4(host)) return null
        }
        if (!PORT.matches(port) || port.toInt() > 65_535) return null
        return if (value.startsWith("[")) "[${host.lowercase()}]:$port" else "$host:$port"
    }

    private fun isIpv4(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 && octets.all { IPV4_OCTET.matches(it) && it.toInt() <= 255 }
    }

    private fun isIpv6(host: String): Boolean {
        val halves = host.split("::")
        return when (halves.size) {
            1 -> groups(halves[0])?.size == 8
            2 -> {
                val head = if (halves[0].isEmpty()) emptyList() else groups(halves[0]) ?: return false
                val tail = if (halves[1].isEmpty()) emptyList() else groups(halves[1]) ?: return false
                head.size + tail.size <= 7
            }
            else -> false
        }
    }

    private fun groups(part: String): List<String>? = part.split(':').takeIf { g -> g.all { HEX_GROUP.matches(it) } }
}
