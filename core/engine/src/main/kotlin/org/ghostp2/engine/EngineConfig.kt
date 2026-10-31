package org.ghostp2.engine

/** Parámetros del motor (tiempos en milisegundos). */
data class EngineConfig(
    val virtualPort: Int = 7420,
    val invitationTtlMillis: Long = 600_000,
    val backoffMinMillis: Long = 15_000,
    val backoffMaxMillis: Long = 300_000,
    val unpairDeliveryWindowMillis: Long = 120_000,
    val connectTimeoutMillis: Long = 60_000,
    /** Tiempo máximo para que Tor llegue a `Ready` al arrancar el motor. */
    val torBootstrapTimeoutMillis: Long = 180_000,
    /** Tiempo total máximo para volver a registrar las autorizaciones de cliente al arrancar. */
    val clientAuthRegistrationTimeoutMillis: Long = 30_000,
)
