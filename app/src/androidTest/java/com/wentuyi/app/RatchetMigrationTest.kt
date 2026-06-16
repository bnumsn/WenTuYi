package com.wentuyi.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks the persisted ratchet-state JSON schema. After moving the ratchet crypto into
 * :shared-protocol, [DoubleRatchet.serialize]/[DoubleRatchet.deserialize] became a thin app-side
 * codec over `protocol.DoubleRatchet.State`. A user upgrading mid-conversation has WTY5 sessions
 * already persisted in the *old* schema; this hardcodes one such blob and asserts it still
 * decodes field-for-field, so a future schema change can't silently break live sessions.
 */
@RunWith(AndroidJUnit4::class)
class RatchetMigrationTest {

    // A frozen pre-migration ratchet state (one skipped key, no receiving chain yet).
    private val LEGACY_JSON = """
        {"rk":"$RK","dhsPub":"$DHS_PUB","dhsPriv":"$DHS_PRIV","dhr":null,
         "cks":"$CKS","ckr":null,"ns":3,"nr":0,"pn":1,
         "skipped":{"$SKIP_KEY":"$SKIP_MK"}}
    """.trimIndent().replace("\n", "").replace(" ", "")

    @Test fun legacyJsonStillDecodesFieldForField() {
        val s = DoubleRatchet.deserialize(LEGACY_JSON)
        assertArrayEquals(dec(RK), s.rk)
        assertArrayEquals(dec(DHS_PUB), s.dhsPub)
        assertArrayEquals(dec(DHS_PRIV), s.dhsPriv)
        assertNull(s.dhr)
        assertArrayEquals(dec(CKS), s.cks)
        assertNull(s.ckr)
        assertEquals(3, s.ns)
        assertEquals(0, s.nr)
        assertEquals(1, s.pn)
        assertEquals(1, s.skipped.size)
        assertArrayEquals(dec(SKIP_MK), s.skipped[SKIP_KEY])
    }

    @Test fun reserializeRoundTripsAllFields() {
        val again = DoubleRatchet.deserialize(DoubleRatchet.serialize(DoubleRatchet.deserialize(LEGACY_JSON)))
        assertArrayEquals(dec(RK), again.rk)
        assertArrayEquals(dec(CKS), again.cks)
        assertEquals(3, again.ns)
        assertEquals(1, again.pn)
        assertArrayEquals(dec(SKIP_MK), again.skipped[SKIP_KEY])
    }

    private fun dec(b64: String) = Base64.decode(b64, Base64.NO_WRAP)

    private companion object {
        // Deterministic, content-irrelevant 32-byte blobs (the codec never inspects them).
        val RK = b64(ByteArray(32) { it.toByte() })
        val DHS_PUB = b64(ByteArray(32) { (it + 1).toByte() })
        val DHS_PRIV = b64(ByteArray(32) { (it + 2).toByte() })
        val CKS = b64(ByteArray(32) { (it + 3).toByte() })
        val SKIP_MK = b64(ByteArray(32) { (it + 4).toByte() })
        const val SKIP_KEY = "AAAA:0"
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
