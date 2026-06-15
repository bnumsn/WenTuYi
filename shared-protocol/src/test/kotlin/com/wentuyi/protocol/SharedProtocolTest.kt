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

        assertTrue(payload.startsWith(SecurePayloadCodec.PREFIX_V4))
        assertEquals(SecurePayloadCodec.KEY_MODE_PASSPHRASE, SecurePayloadCodec.peekKeyMode(payload))
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
        assertEquals(SecurePayloadCodec.KEY_MODE_SESSION_KEY, SecurePayloadCodec.peekKeyMode(payload))
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
    fun v4RejectsOutOfRangeArgonParams() {
        val payload = SecurePayloadCodec.encryptTextToPayload("hi", "k")
        assertTrue(payload.startsWith(SecurePayloadCodec.PREFIX_V4))
        val packed = Encoding.b64Decode(payload.substring(SecurePayloadCodec.PREFIX_V4.length))
        // memKb header field (offset 3..6) → 0xFFFFFFFF, far above the 256 MiB clamp.
        // Must fail fast (clamp), never attempt a multi-GiB Argon2 allocation.
        packed[3] = 0xFF.toByte(); packed[4] = 0xFF.toByte()
        packed[5] = 0xFF.toByte(); packed[6] = 0xFF.toByte()
        val tampered = SecurePayloadCodec.PREFIX_V4 + Encoding.b64(packed)
        assertFails { SecurePayloadCodec.decryptPayload(tampered, "k") }
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

    // ─── Double Ratchet (WTY5 / PFS) ─────────────────────────────────────────

    private class Pair2(val alice: DoubleRatchet.State, val bob: DoubleRatchet.State)

    private fun establishRatchet(): Pair2 {
        val a = KeyExchange.generateIdentity()
        val b = KeyExchange.generateIdentity()
        // Initial root key is deterministic from the two identity keys.
        assertContentEquals(
            DoubleRatchet.initialRootKey(a, b.publicKey),
            DoubleRatchet.initialRootKey(b, a.publicKey),
        )
        val aFirst = DoubleRatchet.isInitiator(a.publicKey, b.publicKey)
        val aliceId = if (aFirst) a else b
        val bobId = if (aFirst) b else a
        return Pair2(
            DoubleRatchet.initAlice(DoubleRatchet.initialRootKey(aliceId, bobId.publicKey), bobId.publicKey),
            DoubleRatchet.initBob(DoubleRatchet.initialRootKey(bobId, aliceId.publicKey), bobId),
        )
    }

    @Test
    fun ratchetBidirectionalAndOutOfOrder() {
        val (alice, bob) = establishRatchet().let { it.alice to it.bob }

        val m1 = DoubleRatchet.encrypt(alice, "你好 Bob".toByteArray(Charsets.UTF_8))
        assertTrue(m1.startsWith(DoubleRatchet.PREFIX_V5))
        assertEquals("你好 Bob", String(DoubleRatchet.decrypt(bob, m1), Charsets.UTF_8))

        // Bob replies → triggers a DH ratchet step on both sides.
        val r1 = DoubleRatchet.encrypt(bob, "收到 Alice".toByteArray(Charsets.UTF_8))
        assertEquals("收到 Alice", String(DoubleRatchet.decrypt(alice, r1), Charsets.UTF_8))

        // Alice sends three; Bob receives them out of order (#3, #1, #2).
        val a1 = DoubleRatchet.encrypt(alice, "one".toByteArray())
        val a2 = DoubleRatchet.encrypt(alice, "two".toByteArray())
        val a3 = DoubleRatchet.encrypt(alice, "three".toByteArray())
        assertEquals("three", String(DoubleRatchet.decrypt(bob, a3)))
        assertEquals("one", String(DoubleRatchet.decrypt(bob, a1)))
        assertEquals("two", String(DoubleRatchet.decrypt(bob, a2)))
    }

    @Test
    fun ratchetMessageKeyConsumedOnce() {
        val (alice, bob) = establishRatchet().let { it.alice to it.bob }
        val m = DoubleRatchet.encrypt(alice, "secret".toByteArray())
        assertEquals("secret", String(DoubleRatchet.decrypt(bob, m)))
        // Replaying the same in-order message must fail — its chain key is already gone (PFS).
        assertFails { DoubleRatchet.decrypt(bob, m) }
    }

    @Test
    fun ratchetDecryptIsTransactionalOnFailure() {
        val (alice, bob) = establishRatchet().let { it.alice to it.bob }
        val unrelated = establishRatchet().bob   // a different contact's state
        val m = DoubleRatchet.encrypt(alice, "for bob only".toByteArray())

        // decrypt() itself is transactional: a wrong-contact attempt throws and leaves the
        // passed state byte-for-byte untouched — no defensive clone required by the caller.
        val rkBefore = unrelated.rk.copyOf()
        val nrBefore = unrelated.nr
        val dhrWasNull = unrelated.dhr == null
        assertFails { DoubleRatchet.decrypt(unrelated, m) }
        assertContentEquals(rkBefore, unrelated.rk)
        assertEquals(nrBefore, unrelated.nr)
        assertEquals(dhrWasNull, unrelated.dhr == null)
        // The right contact still decrypts.
        assertEquals("for bob only", String(DoubleRatchet.decrypt(bob, m)))
    }

    @Test
    fun ratchetManyDhRoundTrips() {
        val (alice, bob) = establishRatchet().let { it.alice to it.bob }
        // Alternate sender each turn so a fresh DH ratchet step runs every message.
        repeat(12) { i ->
            val a2b = DoubleRatchet.encrypt(alice, "a$i".toByteArray())
            assertEquals("a$i", String(DoubleRatchet.decrypt(bob, a2b)))
            val b2a = DoubleRatchet.encrypt(bob, "b$i".toByteArray())
            assertEquals("b$i", String(DoubleRatchet.decrypt(alice, b2a)))
        }
    }

    @Test
    fun ratchetSkippedCacheIsGloballyBounded() {
        val (alice, bob) = establishRatchet().let { it.alice to it.bob }
        // One long sending chain (no Bob reply → no DH step), received at <1000 gaps so the
        // per-step MAX_SKIP isn't hit but the global cache accumulates past its 2000 cap.
        val msgs = (0 until 2400).map { DoubleRatchet.encrypt(alice, "m$it".toByteArray()) }
        DoubleRatchet.decrypt(bob, msgs[900])
        DoubleRatchet.decrypt(bob, msgs[1800])
        DoubleRatchet.decrypt(bob, msgs[2399])
        assertTrue(bob.skipped.size <= 2000)
        // A recent skipped message still decrypts; the very oldest was evicted.
        assertEquals("m2300", String(DoubleRatchet.decrypt(bob, msgs[2300])))
        assertFails { DoubleRatchet.decrypt(bob, msgs[5]) }
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
