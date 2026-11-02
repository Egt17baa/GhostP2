package org.ghostp2.engine.bridges

/** Ajuste «Puentes» (R30); [NONE] = conexión directa a Tor, como antes. */
enum class BridgeMode { NONE, OBFS4, SNOWFLAKE, CUSTOM }

/** Puentes con los que arranca Tor; [kinds] son los transportes que hay que iniciar. */
data class BridgeSetup(val lines: List<BridgeLine>) {
    val kinds: Set<PtKind> = lines.mapTo(LinkedHashSet()) { it.kind }
}

/**
 * Puentes de [mode]; `null` = sin puentes. Con [BridgeMode.CUSTOM], [custom] son las líneas guardadas, y si no
 * pasan [BridgeLines.parse] o no hay ninguna también da `null`: los ajustes guardados nunca llegan así, porque
 * `AppSettings.sanitized()` lo convierte en [BridgeMode.NONE].
 */
fun bridgeSetupFor(mode: BridgeMode, custom: List<String>): BridgeSetup? = when (mode) {
    BridgeMode.NONE -> null
    BridgeMode.OBFS4 -> BridgeSetup(BuiltInBridges.OBFS4)
    BridgeMode.SNOWFLAKE -> BridgeSetup(BuiltInBridges.SNOWFLAKE)
    BridgeMode.CUSTOM -> (BridgeLines.parse(custom.joinToString("\n")) as? ParseResult.Ok)
        ?.lines
        ?.takeIf { it.isNotEmpty() }
        ?.let(::BridgeSetup)
}
