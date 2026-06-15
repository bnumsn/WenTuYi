package com.wentuyi.protocol

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signal-style Double Ratchet for 文图易 — gives forward secrecy + post-compromise
 * recovery over the app's async / lossy / no-server channel (WeChat/QQ copy-paste, QR).
 *
 * Differences from the textbook Signal ratchet, dictated by this app having **no prekey
 * server and no handshake channel**:
 *  - The initial root key is `HKDF(ECDH(identityA, identityB))` — it reuses the X25519
 *    identity keys the two peers already exchanged-by-QR and verified with the SAS, so no
 *    new handshake is needed.
 *  - Roles are assigned deterministically by public-key order (lower = Alice/initiator),
 *    and Bob's *initial* ratchet keypair is his identity keypair (the only key Alice has
 *    for him). PFS is therefore complete only after Bob's first reply rotates that key —
 *    same caveat as Signal without prekeys; documented, not hidden.
 *  - No header encryption: the sender's current ratchet public key + counters are visible
 *    in the WTY5 envelope. (Sender *identity* is NOT in the envelope — the receiver trial-
 *    decrypts against each contact's state, so ciphertext never reveals "who sent this".)
 *
 * Message keys are single-use and derived (key‖iv) from the chain via HKDF, so a leaked
 * long-term key can't recover past message keys — the chain keys that produced them are
 * already overwritten.
 *
 * [State] is mutated in place by [encrypt]/[decrypt]. For the trial-decrypt-across-contacts
 * flow, callers must [clone] the state, attempt [decrypt] on the copy, and commit the copy
 * only on success — a failed trial then leaves the real state untouched.
 */
object DoubleRatchet {
    const val PREFIX_V5 = "WTY5:"

    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_SKIP = 1000            // bound on skipped keys per single chain step
    private const val MAX_SKIP_TOTAL = 2000      // global cap on the persisted skipped-key cache
    private const val MAX_PAYLOAD_CHARS = 512 * 1024  // reject absurd inputs before decoding
    private const val HEADER_LEN = 32 + 4 + 4    // ratchetPub(32) + PN(4) + N(4)

    private val INFO_INIT = "WTY5-root-init".toByteArray(StandardCharsets.US_ASCII)
    private val INFO_ROOT = "WTY5-root".toByteArray(StandardCharsets.US_ASCII)
    private val INFO_MSG = "WTY5-msg".toByteArray(StandardCharsets.US_ASCII)
    private val CHAIN_MK = byteArrayOf(0x01)
    private val CHAIN_CK = byteArrayOf(0x02)
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    class State(
        var rk: ByteArray,
        var dhsPub: ByteArray,
        var dhsPriv: ByteArray,
        var dhr: ByteArray?,
        var cks: ByteArray?,
        var ckr: ByteArray?,
        var ns: Int = 0,
        var nr: Int = 0,
        var pn: Int = 0,
        val skipped: MutableMap<String, ByteArray> = LinkedHashMap(),
    )

    /** Deterministic initial root key from the two verified X25519 identity keys. */
    fun initialRootKey(selfIdentity: KeyExchange.Identity, peerIdentityPub: ByteArray): ByteArray {
        val dh = KeyExchange.ecdh(selfIdentity.privateKey, peerIdentityPub)
        try {
            return CryptoUtils.hkdfSha256(dh, ZERO_SALT, INFO_INIT, KEY_BYTES)
        } finally {
            CryptoUtils.wipe(dh)
        }
    }

    /** Lower public key = Alice (initiator). Both peers compute the same role. */
    fun isInitiator(selfIdentityPub: ByteArray, peerIdentityPub: ByteArray): Boolean =
        compareBytes(selfIdentityPub, peerIdentityPub) < 0

    /** Initiator-side init: starts a sending chain against the peer's identity key. */
    fun initAlice(rootKey: ByteArray, peerIdentityPub: ByteArray): State {
        val dhs = KeyExchange.generateIdentity()
        val st = State(
            rk = rootKey.copyOf(), dhsPub = dhs.publicKey, dhsPriv = dhs.privateKey,
            dhr = peerIdentityPub.copyOf(), cks = null, ckr = null,
        )
        val dh = KeyExchange.ecdh(st.dhsPriv, peerIdentityPub)
        try {
            val (newRk, cks) = kdfRoot(st.rk, dh)
            st.rk = newRk; st.cks = cks
        } finally { CryptoUtils.wipe(dh) }
        return st
    }

    /** Responder-side init: its initial ratchet keypair is its identity keypair. */
    fun initBob(rootKey: ByteArray, selfIdentity: KeyExchange.Identity): State =
        State(
            rk = rootKey.copyOf(),
            dhsPub = selfIdentity.publicKey.copyOf(),
            dhsPriv = selfIdentity.privateKey.copyOf(),
            dhr = null, cks = null, ckr = null,
        )

    // ─── Encrypt / decrypt ────────────────────────────────────────────────────

    fun encrypt(state: State, plaintext: ByteArray): String {
        val cks = state.cks ?: throw GeneralSecurityException("棘轮发送链未建立")
        val (newCks, mk) = kdfChain(cks)
        val header = packHeader(state.dhsPub, state.pn, state.ns)
        try {
            val ct = aeadEncrypt(mk, plaintext, header)
            state.cks = newCks
            state.ns += 1
            val packed = ByteArray(header.size + ct.size)
            System.arraycopy(header, 0, packed, 0, header.size)
            System.arraycopy(ct, 0, packed, header.size, ct.size)
            return PREFIX_V5 + Encoding.b64(packed)
        } finally { CryptoUtils.wipe(mk) }
    }

    /** Mutates [state]; throws on any failure. Use [clone] for non-destructive trials. */
    fun decrypt(state: State, payload: String): ByteArray {
        if (!payload.startsWith(PREFIX_V5)) throw GeneralSecurityException("不是 WTY5 棘轮密文")
        if (payload.length > MAX_PAYLOAD_CHARS) throw GeneralSecurityException("棘轮密文过大")
        val raw = Encoding.b64Decode(payload.substring(PREFIX_V5.length))
        if (raw.size <= HEADER_LEN) throw GeneralSecurityException("棘轮密文不完整")
        val header = Arrays.copyOfRange(raw, 0, HEADER_LEN)
        val ct = Arrays.copyOfRange(raw, HEADER_LEN, raw.size)
        val dhr = Arrays.copyOfRange(header, 0, 32)
        val pn = readInt(header, 32)
        val n = readInt(header, 36)

        // 1. A skipped (out-of-order) message we already cached a key for.
        val skipId = skipKey(dhr, n)
        state.skipped.remove(skipId)?.let { mk ->
            try { return aeadDecrypt(mk, ct, header) } finally { CryptoUtils.wipe(mk) }
        }

        // 2. New sending ratchet from the peer → skip the tail of the old chain, then step.
        if (state.dhr == null || !dhr.contentEquals(state.dhr!!)) {
            skipMessageKeys(state, pn)
            dhRatchet(state, dhr)
        }
        // 3. Skip within the current receiving chain up to N, then derive this message key.
        skipMessageKeys(state, n)
        val ckr = state.ckr ?: throw GeneralSecurityException("棘轮接收链未建立")
        val (newCkr, mk) = kdfChain(ckr)
        try {
            val plain = aeadDecrypt(mk, ct, header)
            state.ckr = newCkr
            state.nr += 1
            return plain
        } finally { CryptoUtils.wipe(mk) }
    }

    private fun skipMessageKeys(state: State, until: Int) {
        val ckr = state.ckr ?: return
        if (until - state.nr > MAX_SKIP) throw GeneralSecurityException("跳过的消息过多，拒绝")
        var chain = ckr
        while (state.nr < until) {
            val (newChain, mk) = kdfChain(chain)
            state.skipped[skipKey(state.dhr!!, state.nr)] = mk
            chain = newChain
            state.nr += 1
        }
        state.ckr = chain
        // Global cap so a peer streaming high-N messages can't grow the persisted cache
        // unbounded. LinkedHashMap iteration is insertion order → evict the oldest first.
        while (state.skipped.size > MAX_SKIP_TOTAL) {
            val oldest = state.skipped.keys.iterator().next()
            state.skipped.remove(oldest)
        }
    }

    private fun dhRatchet(state: State, dhrPub: ByteArray) {
        state.pn = state.ns
        state.ns = 0
        state.nr = 0
        state.dhr = dhrPub
        run {
            val dh = KeyExchange.ecdh(state.dhsPriv, dhrPub)
            try { val (rk, ckr) = kdfRoot(state.rk, dh); state.rk = rk; state.ckr = ckr }
            finally { CryptoUtils.wipe(dh) }
        }
        val newDhs = KeyExchange.generateIdentity()
        CryptoUtils.wipe(state.dhsPriv)
        state.dhsPub = newDhs.publicKey
        state.dhsPriv = newDhs.privateKey
        run {
            val dh = KeyExchange.ecdh(state.dhsPriv, dhrPub)
            try { val (rk, cks) = kdfRoot(state.rk, dh); state.rk = rk; state.cks = cks }
            finally { CryptoUtils.wipe(dh) }
        }
    }

    // ─── KDF chains ───────────────────────────────────────────────────────────

    /** Root KDF: salt = current root key, ikm = DH output → (newRootKey, chainKey). */
    private fun kdfRoot(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = CryptoUtils.hkdfSha256(dhOut, rk, INFO_ROOT, KEY_BYTES * 2)
        return out.copyOfRange(0, KEY_BYTES) to out.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
    }

    /** Symmetric chain KDF: messageKey = HMAC(ck, 0x01), nextChainKey = HMAC(ck, 0x02). */
    private fun kdfChain(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = CryptoUtils.hmacSha256(ck, CHAIN_MK)
        val nextCk = CryptoUtils.hmacSha256(ck, CHAIN_CK)
        return nextCk to mk
    }

    // ─── AEAD (key+iv derived from the single-use message key) ─────────────────

    private fun aeadEncrypt(mk: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val (key, iv) = messageKeyIv(mk)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(plaintext)
        } finally { CryptoUtils.wipe(key) }
    }

    private fun aeadDecrypt(mk: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val (key, iv) = messageKeyIv(mk)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        } finally { CryptoUtils.wipe(key) }
    }

    private fun messageKeyIv(mk: ByteArray): Pair<ByteArray, ByteArray> {
        val out = CryptoUtils.hkdfSha256(mk, ZERO_SALT, INFO_MSG, KEY_BYTES + IV_BYTES)
        return out.copyOfRange(0, KEY_BYTES) to out.copyOfRange(KEY_BYTES, KEY_BYTES + IV_BYTES)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Deep copy for non-destructive trial decryption across contacts. */
    fun clone(s: State): State = State(
        rk = s.rk.copyOf(), dhsPub = s.dhsPub.copyOf(), dhsPriv = s.dhsPriv.copyOf(),
        dhr = s.dhr?.copyOf(), cks = s.cks?.copyOf(), ckr = s.ckr?.copyOf(),
        ns = s.ns, nr = s.nr, pn = s.pn,
        skipped = LinkedHashMap(s.skipped.mapValues { it.value.copyOf() }),
    )

    private fun packHeader(ratchetPub: ByteArray, pn: Int, n: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(ratchetPub, 0, h, 0, 32)
        writeInt(h, 32, pn)
        writeInt(h, 36, n)
        return h
    }

    private fun skipKey(dhr: ByteArray, n: Int): String =
        Encoding.Base32.encode(dhr) + ":" + n

    private fun writeInt(t: ByteArray, off: Int, v: Int) {
        t[off] = (v ushr 24).toByte(); t[off + 1] = (v ushr 16).toByte()
        t[off + 2] = (v ushr 8).toByte(); t[off + 3] = v.toByte()
    }

    private fun readInt(s: ByteArray, off: Int): Int =
        ((s[off].toInt() and 0xFF) shl 24) or ((s[off + 1].toInt() and 0xFF) shl 16) or
            ((s[off + 2].toInt() and 0xFF) shl 8) or (s[off + 3].toInt() and 0xFF)

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val c = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}
