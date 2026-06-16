package com.wentuyi.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the stable [ProtocolError] codes that platform shells map to localized messages
 * (the Android app re-localizes these back to Chinese). If a code changes or a failure path
 * stops throwing [ProtocolException], the app's error UX silently breaks — so pin them here.
 */
class ProtocolErrorTest {

    @Test fun lowOrderPublicKeyIsCoded() {
        val id = KeyExchange.generateIdentity()
        val e = assertFailsWith<ProtocolException> { KeyExchange.ecdh(id.privateKey, ByteArray(32)) }
        assertEquals(ProtocolError.LOW_ORDER_KEY, e.code)
    }

    @Test fun backupFailuresAreCoded() {
        assertEquals(
            ProtocolError.NOT_A_BACKUP,
            assertFailsWith<ProtocolException> { KeyExchange.decodeBackup("NOPE-XXXX") }.code,
        )
        // Valid prefix, but the Base32 body decodes to the wrong length.
        assertEquals(
            ProtocolError.BACKUP_LENGTH,
            assertFailsWith<ProtocolException> { KeyExchange.decodeBackup("WTYB1-AAAA") }.code,
        )
        // Tamper a real backup so the CRC no longer matches.
        val real = KeyExchange.encodeBackup(KeyExchange.generateIdentity())
        val flipped = real.dropLast(1) + if (real.last() == 'A') 'B' else 'A'
        assertEquals(
            ProtocolError.BACKUP_CRC_MISMATCH,
            assertFailsWith<ProtocolException> { KeyExchange.decodeBackup(flipped) }.code,
        )
    }

    @Test fun identityQrFailuresAreCoded() {
        assertEquals(
            ProtocolError.NOT_AN_IDENTITY_QR,
            assertFailsWith<ProtocolException> { KeyExchange.decodeIdentityFromQr("hello") }.code,
        )
        assertEquals(
            ProtocolError.IDENTITY_QR_INCOMPLETE,
            assertFailsWith<ProtocolException> { KeyExchange.decodeIdentityFromQr("WTYID1|only-name") }.code,
        )
        assertEquals(
            ProtocolError.IDENTITY_KEY_LENGTH,
            assertFailsWith<ProtocolException> { KeyExchange.decodeIdentityFromQr("WTYID1|bob|AAAA") }.code,
        )
    }

    @Test fun everyErrorCodeHasADistinctName() {
        val names = ProtocolError.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
