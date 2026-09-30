package com.wentuyi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.SecurePayloadCodec
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real ART reclamation; a fake heap snapshot cannot reveal System.gc() deferral. */
@RunWith(AndroidJUnit4::class)
class Argon2BudgetDeviceTest {
    @Test
    fun repeatedDefaultPassphraseOperationsReclaimPriorArgonBlocks() {
        // Run on the normal 256 MiB emulator/app heap. Multiple 64 MiB derivations leave
        // enough garbage to need admission-time collection before ART's allocation limit.
        // Do not call GC from the test: the production admission path must do the work.
        repeat(4) { index ->
            val plain = "连续默认参数加密 $index"
            val payload = SecurePayloadCodec.encryptTextToPayload(plain, "device budget regression")
            assertEquals(plain, SecurePayloadCodec.decryptPayload(payload, "device budget regression"))
        }
    }

    @Test
    fun smallForgedPayloadCannotSelect256MiBOfArgonMemory() {
        val packed = ByteArray(54).apply {
            this[0] = 4
            this[1] = 1
            this[4] = 4 // uint32 big-endian memKB = 0x00040000 = 262144.
            this[7] = 1
            this[8] = 1
        }
        val forged = SecurePayloadCodec.PREFIX_V4 + Encoding.b64(packed)
        assertEquals(77, forged.length)
        try {
            SecurePayloadCodec.decryptPayload(forged, "pw")
            fail("forged parameters must be refused before allocation")
        } catch (expected: GeneralSecurityException) {
            assertTrue(expected.message!!.contains("argon2 params out of safe range"))
        }
    }
}
