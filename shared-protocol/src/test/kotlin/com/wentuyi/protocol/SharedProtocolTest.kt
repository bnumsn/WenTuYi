package com.wentuyi.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private class Pair2(
        val alice: DoubleRatchet.State,
        val bob: DoubleRatchet.State,
        val aliceId: KeyExchange.Identity,
        val bobId: KeyExchange.Identity,
    )

    private fun establishRatchet(epoch: Long = DoubleRatchet.newEpoch()): Pair2 {
        val a = KeyExchange.generateIdentity()
        val b = KeyExchange.generateIdentity()
        // Initial root key is deterministic from the two identity keys *and* the epoch.
        assertContentEquals(
            DoubleRatchet.initialRootKey(a, b.publicKey, epoch),
            DoubleRatchet.initialRootKey(b, a.publicKey, epoch),
        )
        val aFirst = DoubleRatchet.isInitiator(a.publicKey, b.publicKey)
        val aliceId = if (aFirst) a else b
        val bobId = if (aFirst) b else a
        return Pair2(
            DoubleRatchet.initSender(
                DoubleRatchet.initialRootKey(aliceId, bobId.publicKey, epoch), bobId.publicKey, epoch),
            DoubleRatchet.initReceiver(
                DoubleRatchet.initialRootKey(bobId, aliceId.publicKey, epoch), bobId, epoch),
            aliceId,
            bobId,
        )
    }

    @Test
    fun ratchetRootKeyDependsOnEpoch() {
        val a = KeyExchange.generateIdentity()
        val b = KeyExchange.generateIdentity()
        val rk1 = DoubleRatchet.initialRootKey(a, b.publicKey, 1_700_000_000_000L)
        val rk2 = DoubleRatchet.initialRootKey(a, b.publicKey, 1_700_000_000_001L)
        assertFalse(rk1.contentEquals(rk2), "a new epoch must not reproduce the old root key")
    }

    @Test
    fun ratchetHeaderCarriesTheEpochAndRejectsForeignOnes() {
        val epoch = 1_700_000_000_000L
        val p = establishRatchet(epoch)
        val msg = DoubleRatchet.encrypt(p.alice, "hello".toByteArray())
        assertEquals(epoch, DoubleRatchet.peekEpoch(msg))

        // A session from a *different* epoch must reject it as a typed mismatch rather
        // than an indistinguishable AEAD failure.
        val other = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(p.bobId, p.aliceId.publicKey, epoch + 1),
            p.bobId,
            epoch + 1,
        )
        val e = assertFailsWith<DoubleRatchet.EpochMismatch> { DoubleRatchet.decrypt(other, msg) }
        assertEquals(epoch, e.headerEpoch)
        assertEquals(epoch + 1, e.stateEpoch)
    }

    @Test
    fun ratchetSurvivesTheSenderLosingItsState() {
        // The regression this whole mechanism exists for: Alice reinstalls mid-conversation.
        val p = establishRatchet(1_700_000_000_000L)
        assertEquals("m1", String(DoubleRatchet.decrypt(p.bob, DoubleRatchet.encrypt(p.alice, "m1".toByteArray()))))
        assertEquals("r1", String(DoubleRatchet.decrypt(p.alice, DoubleRatchet.encrypt(p.bob, "r1".toByteArray()))))

        // Alice's state is gone. She re-bootstraps from her identity backup under a NEW epoch.
        val newEpoch = 1_700_000_060_000L
        val alice2 = DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(p.aliceId, p.bobId.publicKey, newEpoch),
            p.bobId.publicKey,
            newEpoch,
        )
        val revived = DoubleRatchet.encrypt(alice2, "我重装了".toByteArray(Charsets.UTF_8))

        // Bob's live session can't decrypt it — but the epoch tells him *why*, so he can
        // adopt the new session instead of showing "decrypt failed" forever.
        assertFailsWith<DoubleRatchet.EpochMismatch> { DoubleRatchet.decrypt(p.bob, revived) }
        val bob2 = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(p.bobId, p.aliceId.publicKey, DoubleRatchet.peekEpoch(revived)!!),
            p.bobId,
            DoubleRatchet.peekEpoch(revived)!!,
        )
        assertEquals("我重装了", String(DoubleRatchet.decrypt(bob2, revived), Charsets.UTF_8))

        // …and the revived session is a working two-way ratchet, not a one-shot.
        assertEquals("回来了", String(
            DoubleRatchet.decrypt(alice2, DoubleRatchet.encrypt(bob2, "回来了".toByteArray(Charsets.UTF_8))),
            Charsets.UTF_8))
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
    fun ratchetStateSurvivesTheCodecMidConversation() {
        // The desktop CLI is stateless between invocations, so every ratchet step round-trips
        // through RatchetStateCodec. A field dropped here would silently break the session
        // one message later, so exercise it with skipped keys and both chains populated.
        val p = establishRatchet()
        val msgs = (0 until 4).map { DoubleRatchet.encrypt(p.alice, "m$it".toByteArray()) }

        var bob = RatchetStateCodec.decode(RatchetStateCodec.encode(p.bob))
        assertEquals("m3", String(DoubleRatchet.decrypt(bob, msgs[3])))   // caches 0..2
        bob = RatchetStateCodec.decode(RatchetStateCodec.encode(bob))     // persist mid-skip
        assertEquals(3, bob.skipped.size)
        assertEquals("m1", String(DoubleRatchet.decrypt(bob, msgs[1])))   // from the cache
        assertEquals("m0", String(DoubleRatchet.decrypt(bob, msgs[0])))

        // Both directions still work after the round trip, and the epoch is preserved.
        bob = RatchetStateCodec.decode(RatchetStateCodec.encode(bob))
        assertEquals(p.bob.epoch, bob.epoch)
        val reply = DoubleRatchet.encrypt(bob, "ok".toByteArray())
        val alice = RatchetStateCodec.decode(RatchetStateCodec.encode(p.alice))
        assertEquals("ok", String(DoubleRatchet.decrypt(alice, reply)))
    }

    @Test
    fun ratchetStateCodecRejectsGarbage() {
        assertFails { RatchetStateCodec.decodeText("bm90LWEtc3RhdGU=") }
        val good = RatchetStateCodec.encode(establishRatchet().alice)
        good[0] = 99  // unsupported version byte
        assertFails { RatchetStateCodec.decode(good) }
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
    fun imageConvenienceMethodsRoundTrip() {
        val image = ByteArray(40) { (it * 7).toByte() }
        val pp = SecurePayloadCodec.decryptEnvelope(
            SecurePayloadCodec.encryptImageToPayload(image, "pw"), "pw",
        )
        assertEquals(SecurePayloadCodec.TYPE_IMAGE, pp.type)
        assertContentEquals(image, pp.data)

        val sessionKey = ByteArray(32) { it.toByte() }
        val sp = SecurePayloadCodec.decryptEnvelopeWithSessionKey(
            SecurePayloadCodec.encryptImageWithSessionKey(image, sessionKey), sessionKey,
        )
        assertEquals(SecurePayloadCodec.TYPE_IMAGE, sp.type)
        assertContentEquals(image, sp.data)

        val wrapped = SecurePayloadCodec.textPayload("hi".toByteArray())
        assertEquals(SecurePayloadCodec.TYPE_TEXT, wrapped.type)
        assertEquals("hi", wrapped.text())
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
