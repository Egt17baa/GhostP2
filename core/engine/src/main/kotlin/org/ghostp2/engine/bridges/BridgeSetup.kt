package org.ghostp2.engine.bridges

/** Ajuste «Puentes» (R30); [NONE] = conexión directa a Tor, como antes. */
enum class BridgeMode { NONE, OBFS4, SNOWFLAKE, CUSTOM }

/** Puentes con los que arranca Tor; [kinds] son los transportes que hay que iniciar. */
data class BridgeSetup(val lines: List<BridgeLine>) {
    val kinds: Set<PtKind> = lines.mapTo(LinkedHashSet()) { it.kind }
}

/**
 * Puentes de [mode]; `null` **solo** con [BridgeMode.NONE] (sin puentes). Con [BridgeMode.CUSTOM], [custom] son las
 * líneas guardadas: si no pasan [BridgeLines.parse] o no hay ninguna, el resultado es un [BridgeSetup] **vacío**, que
 * falla cerrado (el transporte lo da como error de puente sin arrancar Tor). Nunca se convierte en conexión directa.
 */
fun bridgeSetupFor(mode: BridgeMode, custom: List<String>): BridgeSetup? = when (mode) {
    BridgeMode.NONE -> null
    BridgeMode.OBFS4 -> BridgeSetup(BuiltInBridges.OBFS4)
    BridgeMode.SNOWFLAKE -> BridgeSetup(BuiltInBridges.SNOWFLAKE)
    BridgeMode.CUSTOM -> BridgeSetup((BridgeLines.parse(custom.joinToString("\n")) as? ParseResult.Ok)?.lines.orEmpty())
}
