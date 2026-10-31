package org.ghostp2.data

import com.google.crypto.tink.Aead

/** [KeyBackend] en memoria: simula keyset guardado/confirmado y fallos de Keystore. */
internal class FakeKeyBackend(
    var stored: Boolean = false,
    var confirmed: Boolean = false,
    var masterPresent: Boolean? = true,
    var key: Aead = TestAeads.newAead(),
) : KeyBackend {
    var openFailure: Exception? = null
    var generateFailure: Exception? = null
    var opens = 0
    var generates = 0
    var resets = 0

    override fun keysetStored() = stored

    override fun keysetConfirmed() = confirmed

    override fun masterKeyPresent() = masterPresent

    override fun open(): Aead {
        opens++
        openFailure?.let { throw it }
        return key
    }

    override fun generate(): Aead {
        generates++
        generateFailure?.let { throw it }
        key = TestAeads.newAead()
        stored = true
        confirmed = true
        masterPresent = true
        return key
    }

    override fun reset() {
        resets++
        stored = false
        confirmed = false
    }
}
