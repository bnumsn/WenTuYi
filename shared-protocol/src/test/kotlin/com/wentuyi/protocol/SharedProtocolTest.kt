package com.wentuyi.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SharedProtocolTest {
    @Test
    fun passphrasePayloadRoundTripsAndRejectsWrongKey() {
        val payload = SecurePayloadCodec.encryptTextToPayload("hello 文图易", "correct horse")

        assertTrue(payload.startsWith(SecurePayloadCodec.PREFIX_V3))
        assertEquals(SecurePayloadCodec.KEY_MODE_PASSPHRASE, SecurePayloadCodec.peekV3KeyMode(payload))
        assertEquals("hello 文图易", SecurePayloadCodec.decryptPayload(payload, "correct horse"))
        assertFails { SecurePayloadCodec.decryptPayload(payload, "wrong horse") }
    }

    @Test
    fun sessionKeyPayloadRoundTripsBetweenIdentities() {
        val alice = KeyExchange.generateIdentity()
        val bob = KeyExchange.generateIdentity()
        val aliceSecret = KeyExchange.deriveSharedSecret(alice, bob.publicKey)
        val bobSecret = KeyExchange.deriveSharedSecret(bob, alice.publicKey)

        assertContentEquals(aliceSecret, bobSecret)
        assertEquals(
            KeyExchange.shortAuthString(alice, bob.publicKey),
            KeyExchange.shortAuthString(bob, alice.publicKey),
        )

        val payload = SecurePayloadCodec.encryptTextWithSessionKey("session 文图易", aliceSecret)
        assertEquals(SecurePayloadCodec.KEY_MODE_SESSION_KEY, SecurePayloadCodec.peekV3KeyMode(payload))
        assertEquals(
            "session 文图易",
            SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, bobSecret).text(),
        )
    }

    @Test
    fun identityQrAndBackupRoundTrip() {
        val identity = KeyExchange.generateIdentity()
        val qr = KeyExchange.encodeIdentityForQr("alice|mobile", identity.publicKey)
        val (name, publicKey) = KeyExchange.decodeIdentityFromQr(qr)
        val restored = KeyExchange.decodeBackup(KeyExchange.encodeBackup(identity))

        assertEquals("alice/mobile", name)
        assertContentEquals(identity.publicKey, publicKey)
        assertContentEquals(identity.publicKey, restored.publicKey)
        assertContentEquals(identity.privateKey, restored.privateKey)
    }

    @Test
    fun imagePageAndChunkEnvelopesRoundTripWithMetadata() {
        val img = ByteArray(64) { it.toByte() }

        val pagePayload = SecurePayloadCodec.encryptImagePageToPayload(img, 2, 5, "k")
        val page = SecurePayloadCodec.decryptEnvelope(pagePayload, "k")
        assertTrue(page.isImagePage())
        assertEquals(2, page.pageNumber)
        assertEquals(5, page.pageTotal)
        assertContentEquals(img, page.data)

        val chunkPayload = SecurePayloadCodec.encryptImageChunkToPayload(img, 1, 3, 999, "k")
        val chunk = SecurePayloadCodec.decryptEnvelope(chunkPayload, "k")
        assertTrue(chunk.isImageChunk())
        assertEquals(1, chunk.pageNumber)   // chunk number reuses the pageNumber slot
        assertEquals(3, chunk.pageTotal)
        assertEquals(999, chunk.totalBytes)
        assertContentEquals(img, chunk.data)
    }

    @Test
    fun payloadChunksAssembleOutOfOrderAndRejectTampering() {
        val payload = SecurePayloadCodec.PREFIX_V3 + "A".repeat(2_200)
        val chunks = PayloadChunks.chunkPayload(payload)

        assertTrue(chunks.size > 1)
        assertEquals(payload, PayloadChunks.assemblePayloadFromTexts(chunks.asReversed()))

        val tampered = chunks.toMutableList()
        tampered[tampered.lastIndex] = tampered.last().dropLast(1) + "B"
        assertFails { PayloadChunks.assemblePayloadFromTexts(tampered) }
    }
}
