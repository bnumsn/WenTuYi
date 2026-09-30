package com.wentuyi.protocol

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadLimitsTest {
    private val sessionKey = ByteArray(32) { it.toByte() }

    @Test
    fun v4LargestTextAndImageCanBeDecrypted() {
        val text = "a".repeat(SecurePayloadCodec.MAX_PLAINTEXT_BYTES)
        val payload = SecurePayloadCodec.encryptTextWithSessionKey(text, sessionKey)
        assertTrue(payload.length <= PayloadLimits.MAX_PAYLOAD_CHARS)
        assertEquals(text, SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, sessionKey).text())

        val image = ByteArray(SecurePayloadCodec.MAX_PLAINTEXT_BYTES) { (it % 251).toByte() }
        val imagePayload = SecurePayloadCodec.encryptImageWithSessionKey(image, sessionKey)
        assertTrue(imagePayload.length <= PayloadLimits.MAX_PAYLOAD_CHARS)
        assertContentEquals(image, SecurePayloadCodec.decryptEnvelopeWithSessionKey(imagePayload, sessionKey).data)
    }

    @Test
    fun v4OversizedBodiesAreRejectedBeforeEncryptionIncludingImageMetadata() {
        val tooLong = "a".repeat(SecurePayloadCodec.MAX_PLAINTEXT_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { SecurePayloadCodec.encryptTextWithSessionKey(tooLong, sessionKey) }
        assertFailsWith<IllegalArgumentException> { SecurePayloadCodec.encryptTextToPayload(tooLong, "password") }
        assertFailsWith<IllegalArgumentException> {
            SecurePayloadCodec.encryptImageWithSessionKey(ByteArray(tooLong.length), sessionKey)
        }
        assertFailsWith<IllegalArgumentException> {
            SecurePayloadCodec.encryptImageToPayload(ByteArray(tooLong.length), "pw")
        }
        assertFailsWith<IllegalArgumentException> {
            SecurePayloadCodec.encryptImagePageToPayload(ByteArray(SecurePayloadCodec.MAX_IMAGE_PAGE_BYTES + 1), 1, 1, "pw")
        }
        assertFailsWith<IllegalArgumentException> {
            SecurePayloadCodec.encryptImageChunkToPayload(ByteArray(SecurePayloadCodec.MAX_IMAGE_CHUNK_BYTES + 1), 1, 1, 400_000, "pw")
        }
    }

    @Test
    fun v4LargestImagePageAndChunkIncludeMetadataInTheBudget() {
        val page = ByteArray(SecurePayloadCodec.MAX_IMAGE_PAGE_BYTES) { (it % 251).toByte() }
        val pagePayload = SecurePayloadCodec.encryptImagePageToPayload(page, 2, 3, "pw")
        assertTrue(pagePayload.length <= PayloadLimits.MAX_PAYLOAD_CHARS)
        val decryptedPage = SecurePayloadCodec.decryptEnvelope(pagePayload, "pw")
        assertEquals(2, decryptedPage.pageNumber)
        assertEquals(3, decryptedPage.pageTotal)
        assertContentEquals(page, decryptedPage.data)

        val chunk = ByteArray(SecurePayloadCodec.MAX_IMAGE_CHUNK_BYTES) { (it % 251).toByte() }
        val chunkPayload = SecurePayloadCodec.encryptImageChunkToPayload(chunk, 1, 2, 500_000, "pw")
        assertTrue(chunkPayload.length <= PayloadLimits.MAX_PAYLOAD_CHARS)
        val decryptedChunk = SecurePayloadCodec.decryptEnvelope(chunkPayload, "pw")
        assertEquals(1, decryptedChunk.pageNumber)
        assertEquals(2, decryptedChunk.pageTotal)
        assertEquals(500_000, decryptedChunk.totalBytes)
        assertContentEquals(chunk, decryptedChunk.data)
    }

    @Test
    fun utf8LimitCountsBytesAndSurrogatePairsBeforeEncoding() {
        val text = "aé文😀"
        assertContentEquals(text.toByteArray(Charsets.UTF_8), PayloadLimits.utf8Bytes(text, 10))
        assertFailsWith<IllegalArgumentException> { PayloadLimits.utf8Bytes(text, 9) }
        for (unpaired in listOf("\uD800x", "x\uDC00", "\uD800\uD800")) {
            assertContentEquals(unpaired.toByteArray(Charsets.UTF_8), PayloadLimits.utf8Bytes(unpaired, 2))
        }
        val cjk = "文".repeat(SecurePayloadCodec.MAX_PLAINTEXT_BYTES / 3 + 1)
        assertFailsWith<IllegalArgumentException> { SecurePayloadCodec.encryptTextWithSessionKey(cjk, sessionKey) }
    }

    @Test
    fun everyVersionRejectsOversizedInputBeforeBase64Decoding() {
        for (version in 1..4) {
            val oversized = "WTY$version:" + "!".repeat(PayloadLimits.MAX_PAYLOAD_CHARS - 4)
            // '!' would throw IllegalArgumentException if Base64 decoding were attempted.
            assertFailsWith<GeneralSecurityException> { SecurePayloadCodec.decryptEnvelope(oversized, "password") }
            assertNull(SecurePayloadCodec.peekKeyMode(oversized))
            if (version >= 3) {
                assertFailsWith<GeneralSecurityException> {
                    SecurePayloadCodec.decryptEnvelopeWithSessionKey(oversized, sessionKey)
                }
            }
        }
        val oversized = "WTY5:" + "!".repeat(PayloadLimits.MAX_PAYLOAD_CHARS - 4)
        val receiver = ratchetPair().second
        val before = RatchetStateCodec.encode(receiver)
        assertFailsWith<GeneralSecurityException> { DoubleRatchet.decrypt(receiver, oversized) }
        assertNull(DoubleRatchet.peekEpoch(oversized))
        assertContentEquals(before, RatchetStateCodec.encode(receiver))
    }

    @Test
    fun ratchetLargestMessageRoundTripsAndRejectedSendDoesNotConsumeItsKey() {
        val (sender, receiver) = ratchetPair()
        val before = RatchetStateCodec.encode(sender)
        for (size in listOf(DoubleRatchet.MAX_PLAINTEXT_BYTES + 1, 400_000)) {
            assertFailsWith<IllegalArgumentException> { DoubleRatchet.encrypt(sender, ByteArray(size)) }
            assertContentEquals(before, RatchetStateCodec.encode(sender))
        }
        val plain = ByteArray(DoubleRatchet.MAX_PLAINTEXT_BYTES) { (it % 251).toByte() }
        val payload = DoubleRatchet.encrypt(sender, plain)
        assertTrue(payload.length <= PayloadLimits.MAX_PAYLOAD_CHARS)
        assertContentEquals(plain, DoubleRatchet.decrypt(receiver, payload))
        assertEquals(1, sender.ns)
        assertEquals(1, receiver.nr)
    }

    @Test
    fun truncatedV4IsRejectedBeforeKeyDerivation() {
        val raw = ByteArray(37 + 15) // One byte short of a complete GCM tag.
        raw[0] = 4
        raw[1] = 1
        val payload = SecurePayloadCodec.PREFIX_V4 + Encoding.b64(raw)
        val error = assertFailsWith<GeneralSecurityException> { SecurePayloadCodec.decryptPayload(payload, "pw") }
        assertEquals("payload incomplete", error.message)
    }

    private fun ratchetPair(): Pair<DoubleRatchet.State, DoubleRatchet.State> {
        val sender = KeyExchange.generateIdentity()
        val receiver = KeyExchange.generateIdentity()
        val epoch = 1_700_000_000_000L
        return DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(sender, receiver.publicKey, epoch), receiver.publicKey, epoch,
        ) to DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(receiver, sender.publicKey, epoch), receiver, epoch,
        )
    }
}
