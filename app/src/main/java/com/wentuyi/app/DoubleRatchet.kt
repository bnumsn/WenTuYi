package com.wentuyi.app

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Android port of the shared-protocol Signal-style Double Ratchet (WTY5 / PFS). Logic is
 * identical to [com.wentuyi.protocol.DoubleRatchet] (verified by the JVM suite); this copy
 * swaps in `android.util.Base64` and adds [serialize]/[deserialize] for per-contact
 * persistence in [WentuyiSettings].
 *
 * Forward secrecy + post-compromise recovery over the app's async/lossy/no-server channel.
 * Roles are deterministic by public-key order; the initial root key reuses the SAS-verified
 * X25519 identity ECDH; sender identity is NOT in the ciphertext. [decrypt] is transactional
 * (commits into the passed [State] only after the AEAD tag verifies), so the receiver can
 * trial-decrypt each contact directly without a defensive [clone].
 */
object DoubleRatchet {
    const val PREFIX_V5 = "WTY5:"

    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_SKIP = 1000
    private const val MAX_SKIP_TOTAL = 2000
    private const val MAX_PAYLOAD_CHARS = 512 * 1024
    private const val HEADER_LEN = 32 + 4 + 4

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

    fun initialRootKey(selfIdentity: KeyExchange.Identity, peerIdentityPub: ByteArray): ByteArray {
        val dh = KeyExchange.ecdh(selfIdentity.privateKey, peerIdentityPub)
        try {
            return CryptoUtils.hkdfSha256(dh, ZERO_SALT, INFO_INIT, KEY_BYTES)
        } finally { CryptoUtils.wipe(dh) }
    }

    fun isInitiator(selfIdentityPub: ByteArray, peerIdentityPub: ByteArray): Boolean =
        compareBytes(selfIdentityPub, peerIdentityPub) < 0

    fun initAlice(rootKey: ByteArray, peerIdentityPub: ByteArray): State {
        val dhs = KeyExchange.generateIdentity()
        val st = State(
            rk = rootKey.copyOf(), dhsPub = dhs.publicKey, dhsPriv = dhs.privateKey,
            dhr = peerIdentityPub.copyOf(), cks = null, ckr = null,
        )
        val dh = KeyExchange.ecdh(st.dhsPriv, peerIdentityPub)
        try {
            val (newRk, cks) = kdfRoot(st.rk, dh); st.rk = newRk; st.cks = cks
        } finally { CryptoUtils.wipe(dh) }
        return st
    }

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
            CryptoUtils.wipe(state.cks)  // old sending chain key superseded
            state.cks = newCks
            state.ns += 1
            val packed = ByteArray(header.size + ct.size)
            System.arraycopy(header, 0, packed, 0, header.size)
            System.arraycopy(ct, 0, packed, header.size, ct.size)
            return PREFIX_V5 + Base64.encodeToString(packed, Base64.NO_WRAP)
        } finally { CryptoUtils.wipe(mk) }
    }

    /**
     * Transactional: commits the advanced ratchet back into [state] only after the AEAD
     * tag verifies, so a forged/corrupt message throws and leaves [state] untouched.
     */
    fun decrypt(state: State, payload: String): ByteArray {
        val work = clone(state)
        val plain = decryptOn(work, payload)
        commit(state, work)
        return plain
    }

    private fun commit(dst: State, src: State) {
        CryptoUtils.wipe(dst.rk); CryptoUtils.wipe(dst.dhsPriv)
        CryptoUtils.wipe(dst.cks); CryptoUtils.wipe(dst.ckr)
        dst.skipped.values.forEach { CryptoUtils.wipe(it) }
        dst.skipped.clear(); dst.skipped.putAll(src.skipped)
        dst.rk = src.rk; dst.dhsPub = src.dhsPub; dst.dhsPriv = src.dhsPriv
        dst.dhr = src.dhr; dst.cks = src.cks; dst.ckr = src.ckr
        dst.ns = src.ns; dst.nr = src.nr; dst.pn = src.pn
    }

    private fun decryptOn(state: State, payload: String): ByteArray {
        if (!payload.startsWith(PREFIX_V5)) throw GeneralSecurityException("不是 WTY5 棘轮密文")
        if (payload.length > MAX_PAYLOAD_CHARS) throw GeneralSecurityException("棘轮密文过大")
        val raw = Base64.decode(payload.substring(PREFIX_V5.length), Base64.NO_WRAP)
        if (raw.size <= HEADER_LEN) throw GeneralSecurityException("棘轮密文不完整")
        val header = Arrays.copyOfRange(raw, 0, HEADER_LEN)
        val ct = Arrays.copyOfRange(raw, HEADER_LEN, raw.size)
        val dhr = Arrays.copyOfRange(header, 0, 32)
        val pn = readInt(header, 32)
        val n = readInt(header, 36)

        val skipId = skipKey(dhr, n)
        state.skipped.remove(skipId)?.let { mk ->
            try { return aeadDecrypt(mk, ct, header) } finally { CryptoUtils.wipe(mk) }
        }
        if (state.dhr == null || !dhr.contentEquals(state.dhr!!)) {
            skipMessageKeys(state, pn)
            dhRatchet(state, dhr)
        }
        skipMessageKeys(state, n)
        val ckr = state.ckr ?: throw GeneralSecurityException("棘轮接收链未建立")
        val (newCkr, mk) = kdfChain(ckr)
        try {
            val plain = aeadDecrypt(mk, ct, header)
            CryptoUtils.wipe(state.ckr)  // old receiving chain key superseded
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
        // Global cap on the persisted skipped-key cache; evict oldest (insertion order).
        while (state.skipped.size > MAX_SKIP_TOTAL) {
            val oldest = state.skipped.keys.iterator().next()
            state.skipped.remove(oldest)?.let { CryptoUtils.wipe(it) }
        }
    }

    private fun dhRatchet(state: State, dhrPub: ByteArray) {
        state.pn = state.ns
        state.ns = 0
        state.nr = 0
        state.dhr = dhrPub
        run {
            val dh = KeyExchange.ecdh(state.dhsPriv, dhrPub)
            try {
                val (rk, ckr) = kdfRoot(state.rk, dh)
                CryptoUtils.wipe(state.rk); CryptoUtils.wipe(state.ckr)
                state.rk = rk; state.ckr = ckr
            } finally { CryptoUtils.wipe(dh) }
        }
        val newDhs = KeyExchange.generateIdentity()
        CryptoUtils.wipe(state.dhsPriv)
        state.dhsPub = newDhs.publicKey
        state.dhsPriv = newDhs.privateKey
        run {
            val dh = KeyExchange.ecdh(state.dhsPriv, dhrPub)
            try {
                val (rk, cks) = kdfRoot(state.rk, dh)
                CryptoUtils.wipe(state.rk); CryptoUtils.wipe(state.cks)
                state.rk = rk; state.cks = cks
            } finally { CryptoUtils.wipe(dh) }
        }
    }

    private fun kdfRoot(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = CryptoUtils.hkdfSha256(dhOut, rk, INFO_ROOT, KEY_BYTES * 2)
        return out.copyOfRange(0, KEY_BYTES) to out.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
    }

    private fun kdfChain(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = CryptoUtils.hmacSha256(ck, CHAIN_MK)
        val nextCk = CryptoUtils.hmacSha256(ck, CHAIN_CK)
        return nextCk to mk
    }

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

    fun clone(s: State): State = State(
        rk = s.rk.copyOf(), dhsPub = s.dhsPub.copyOf(), dhsPriv = s.dhsPriv.copyOf(),
        dhr = s.dhr?.copyOf(), cks = s.cks?.copyOf(), ckr = s.ckr?.copyOf(),
        ns = s.ns, nr = s.nr, pn = s.pn,
        skipped = LinkedHashMap(s.skipped.mapValues { it.value.copyOf() }),
    )

    // ─── Serialization for Keystore-wrapped persistence ───────────────────────

    fun serialize(s: State): String {
        val o = JSONObject()
        o.put("rk", b64(s.rk))
        o.put("dhsPub", b64(s.dhsPub))
        o.put("dhsPriv", b64(s.dhsPriv))
        o.put("dhr", s.dhr?.let { b64(it) } ?: JSONObject.NULL)
        o.put("cks", s.cks?.let { b64(it) } ?: JSONObject.NULL)
        o.put("ckr", s.ckr?.let { b64(it) } ?: JSONObject.NULL)
        o.put("ns", s.ns); o.put("nr", s.nr); o.put("pn", s.pn)
        val sk = JSONObject()
        for ((k, v) in s.skipped) sk.put(k, b64(v))
        o.put("skipped", sk)
        return o.toString()
    }

    fun deserialize(json: String): State {
        val o = JSONObject(json)
        val skipped = LinkedHashMap<String, ByteArray>()
        val sk = o.getJSONObject("skipped")
        for (k in sk.keys()) skipped[k] = unb64(sk.getString(k))
        return State(
            rk = unb64(o.getString("rk")),
            dhsPub = unb64(o.getString("dhsPub")),
            dhsPriv = unb64(o.getString("dhsPriv")),
            dhr = o.optString("dhr").takeIf { !o.isNull("dhr") }?.let { unb64(it) },
            cks = o.optString("cks").takeIf { !o.isNull("cks") }?.let { unb64(it) },
            ckr = o.optString("ckr").takeIf { !o.isNull("ckr") }?.let { unb64(it) },
            ns = o.getInt("ns"), nr = o.getInt("nr"), pn = o.getInt("pn"),
            skipped = skipped,
        )
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun packHeader(ratchetPub: ByteArray, pn: Int, n: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(ratchetPub, 0, h, 0, 32)
        writeInt(h, 32, pn); writeInt(h, 36, n)
        return h
    }

    private fun skipKey(dhr: ByteArray, n: Int): String =
        CryptoUtils.Base32.encode(dhr) + ":" + n

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
