package com.wentuyi.app

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * X25519 identity-key + contact-management.
 *
 * Each device persists one identity keypair. To start an end-to-end encrypted session
 * with a peer, the two devices exchange public-key QR codes (created by
 * [encodeIdentityForQr] / decoded by [decodeIdentityFromQr]) and then compute a
 * deterministic shared secret via [deriveSharedSecret]. The 32-byte secret is suitable
 * for direct use with [SecurePayloadCodec.encryptTextWithSessionKey] / *WithSessionKey
 * (mode = KEY_MODE_SESSION_KEY).
 *
 * The 6-digit [shortAuthString] is intended for out-of-band verification (one party
 * reads it aloud, the other confirms) to defeat MITM during QR exchange.
 *
 * Public key is plain X25519 (32 bytes). QR payload uses a "WTYID1|<name>|<base64>"
 * canonical line, easy to ZXing-encode/decode and clearly distinguishable from
 * encrypted-message QR contents.
 */
object KeyExchange {
    const val QR_PREFIX = "WTYID1"
    private const val SHARED_SECRET_LEN = 32
    private val random = SecureRandom()
    private val HKDF_INFO_SAS = "WTY-SAS-v1".toByteArray(StandardCharsets.US_ASCII)
    private val HKDF_INFO_SESSION = "WTY-session-v1".toByteArray(StandardCharsets.US_ASCII)

    data class Identity(val publicKey: ByteArray, val privateKey: ByteArray) {
        val fingerprint: String get() = CryptoUtils.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))

        override fun equals(other: Any?): Boolean =
            other is Identity && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    data class Contact(
        val name: String,
        val publicKey: ByteArray,
        /**
         * True only after the user has personally confirmed that the 8-digit SAS on
         * their device matches what the peer reads from their own device. Defaults
         * to false at scan time — added contacts are "preliminary" until both sides
         * verify out-of-band. The IME marks unverified targets visually so users
         * aren't lulled into thinking a MITM hasn't slipped between them.
         */
        val verified: Boolean = false,
    ) {
        val fingerprint: String get() = CryptoUtils.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))

        // Equality is by publicKey only (name/verified state can drift independently).
        override fun equals(other: Any?): Boolean =
            other is Contact && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    // ─── Identity keypair ─────────────────────────────────────────────────────

    fun getOrCreateIdentity(context: Context): Identity {
        // Try to load; tolerate Keystore-corrupted pref by silently regenerating.
        // The pref-bytes are scrap if loadIdentity throws, so overwriting is safe.
        runCatching { WentuyiSettings.loadIdentity(context) }
            .getOrNull()
            ?.let { (pub, priv) -> return Identity(pub, priv) }
        val identity = generateIdentity()
        WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
        // Reached only when there was no readable identity — either first run (nothing to
        // clear) or a corrupted-identity regen, which is a new identity → same clean-break
        // invariant as replaceIdentity: drop stale sessions / verification.
        onIdentityChanged(context)
        return identity
    }

    fun replaceIdentity(context: Context): Identity {
        val identity = generateIdentity()
        WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
        // New identity is always a clean break — drop all WTY5 sessions and require
        // re-verification (the mutual SAS depends on this identity).
        onIdentityChanged(context)
        return identity
    }

    /**
     * Invalidates everything tied to the old local identity: clears all ratchet sessions
     * (they were rooted in the old identity's ECDH) and marks every contact unverified
     * (the mutual SAS changed, so prior out-of-band verification no longer holds).
     */
    private fun onIdentityChanged(context: Context) {
        WentuyiSettings.clearAllRatchets(context)
        val downgraded = listContacts(context).map { it.copy(verified = false) }
        if (downgraded.any()) {
            val arr = JSONArray()
            for (c in downgraded) arr.put(c.toJson())
            WentuyiSettings.setContactsJson(context, arr.toString())
        }
    }

    fun loadIdentity(context: Context): Identity? =
        WentuyiSettings.loadIdentity(context)?.let { Identity(it.first, it.second) }

    /**
     * True iff a usable identity is on disk *and* decryptable. Onboarding uses this
     * (via [WentuyiSettings.isIdentityRecoverable]) to distinguish "no identity yet"
     * from "identity present but Keystore wiped" so it can route the user to a
     * recovery flow instead of a confused retry loop.
     */
    fun isIdentityReadable(context: Context): Boolean =
        runCatching { WentuyiSettings.loadIdentity(context) != null }.getOrDefault(false)

    /** True iff there's a stored identity but it can't be decrypted (corruption). */
    fun isIdentityCorrupt(context: Context): Boolean =
        WentuyiSettings.hasIdentity(context) && !isIdentityReadable(context)

    /** Drops the corrupted identity pref so the next getOrCreate generates afresh. */
    fun clearCorruptedIdentity(context: Context) {
        WentuyiSettings.clearIdentity(context)
    }

    /** Generates a fresh X25519 keypair — used for identities and for ratchet keys. */
    internal fun generateIdentity(): Identity {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        return Identity(pub, priv)
    }

    // ─── ECDH + session key ───────────────────────────────────────────────────

    /**
     * Computes the 32-byte X25519 shared secret. Identical on both peers.
     *
     * Rejects all-zero output (RFC 7748 §6.1) — that's what you get when the peer
     * sends a low-order public key, and using such a "secret" would let the attacker
     * predict the session key and decrypt every message.
     */
    fun ecdh(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPrivate, 0))
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        } catch (bcRejection: IllegalStateException) {
            // BC ≥ 1.71 raises this when the shared-secret output would be all-zero.
            // Re-surface as our canonical low-order-pubkey rejection.
            throw IllegalArgumentException("不安全的公钥（低阶点）", bcRejection)
        }
        // Defensive double-check for older BC behaviour or future regressions:
        // OR-fold all bytes; zero only if every byte is zero.
        var accumulator = 0
        for (b in out) accumulator = accumulator or (b.toInt() and 0xFF)
        if (accumulator == 0) {
            throw IllegalArgumentException("不安全的公钥（低阶点）")
        }
        return out
    }

    /**
     * Derives a deterministic 32-byte session key for [SecurePayloadCodec]
     * (KEY_MODE_SESSION_KEY). Public keys are sorted lexicographically so both peers
     * derive identical bytes regardless of who's initiating.
     */
    fun deriveSharedSecret(myIdentity: Identity, peerPublic: ByteArray): ByteArray {
        val ecdh = ecdh(myIdentity.privateKey, peerPublic)
        try {
            val (low, high) =
                if (compareBytes(myIdentity.publicKey, peerPublic) <= 0)
                    myIdentity.publicKey to peerPublic
                else peerPublic to myIdentity.publicKey
            val salt = ByteArray(low.size + high.size).apply {
                System.arraycopy(low, 0, this, 0, low.size)
                System.arraycopy(high, 0, this, low.size, high.size)
            }
            return CryptoUtils.hkdfSha256(ecdh, salt, HKDF_INFO_SESSION, SHARED_SECRET_LEN)
        } finally {
            // Wipe the ECDH intermediate so it doesn't outlive its purpose on the heap.
            CryptoUtils.wipe(ecdh)
        }
    }

    /**
     * Derives an 8-digit Short Authentication String for out-of-band verification.
     * Both peers must see the same number for the X25519 exchange to be trusted.
     * 8 digits (~27 bits) makes a MITM's odds of forging a matching SAS ~1-in-10^8
     * per exchange, two orders of magnitude better than the old 6-digit string.
     */
    fun shortAuthString(myIdentity: Identity, peerPublic: ByteArray): String {
        val ecdh = ecdh(myIdentity.privateKey, peerPublic)
        try {
            val (low, high) =
                if (compareBytes(myIdentity.publicKey, peerPublic) <= 0)
                    myIdentity.publicKey to peerPublic
                else peerPublic to myIdentity.publicKey
            val salt = ByteArray(low.size + high.size).apply {
                System.arraycopy(low, 0, this, 0, low.size)
                System.arraycopy(high, 0, this, low.size, high.size)
            }
            val derived = CryptoUtils.hkdfSha256(ecdh, salt, HKDF_INFO_SAS, 4)
            val asInt = ((derived[0].toInt() and 0x7F) shl 24) or
                ((derived[1].toInt() and 0xFF) shl 16) or
                ((derived[2].toInt() and 0xFF) shl 8) or
                (derived[3].toInt() and 0xFF)
            return (asInt % 100_000_000).toString().padStart(8, '0')
        } finally {
            CryptoUtils.wipe(ecdh)
        }
    }

    // ─── Identity backup (Base32 string the user can write down) ──────────────

    private const val BACKUP_PREFIX = "WTYB1"

    /**
     * Encodes the local identity (publicKey 32B + privateKey 32B + CRC32 4B = 68B)
     * as a grouped Base32 string the user can copy onto paper or a password manager.
     * Format: `WTYB1-XXXX-XXXX-…-XXXX` (Base32-encoded, dash-separated 5-char groups).
     *
     * v0.5 added the trailing CRC32; v0.4 backups (64 bytes) are still accepted by
     * [decodeBackup] for migration.
     */
    fun encodeBackup(identity: Identity): String {
        val packed = ByteArray(32 + 32 + 4)
        System.arraycopy(identity.publicKey, 0, packed, 0, 32)
        System.arraycopy(identity.privateKey, 0, packed, 32, 32)
        val crc = java.util.zip.CRC32().apply { update(packed, 0, 64) }.value.toInt()
        packed[64] = (crc ushr 24).toByte()
        packed[65] = (crc ushr 16).toByte()
        packed[66] = (crc ushr 8).toByte()
        packed[67] = (crc and 0xFF).toByte()
        val raw = CryptoUtils.Base32.encode(packed)
        val groups = raw.chunked(5)
        return BACKUP_PREFIX + "-" + groups.joinToString("-")
    }

    /**
     * Inverse of [encodeBackup]. Strips whitespace incl. NBSP / zero-width chars that
     * sneak in when users paste from PDFs or chat apps. Verifies CRC32 (v0.5+) and
     * derives the public key from the private to catch single-char typos.
     */
    fun decodeBackup(text: String): Identity {
        // Strip NBSP, zero-width chars and BOM that can sneak in from PDFs or chat apps.
        // Also strip dashes the grouping format adds.
        val cleaned = text.trim().uppercase()
            .replace(Regex("[\\s\\u00A0\\u200B-\\u200D\\uFEFF\\-]"), "")
        require(cleaned.startsWith(BACKUP_PREFIX)) { "不是文图易身份备份码" }
        val body = cleaned.substring(BACKUP_PREFIX.length)
        val bytes = try { CryptoUtils.Base32.decode(body) }
                    catch (e: Exception) { throw IllegalArgumentException("备份码内容损坏") }
        return when (bytes.size) {
            68 -> decodeBackupV05(bytes)
            64 -> decodeBackupV04(bytes)
            else -> throw IllegalArgumentException("备份码长度异常 (${bytes.size} 字节)")
        }
    }

    private fun decodeBackupV05(bytes: ByteArray): Identity {
        val pub = bytes.copyOfRange(0, 32)
        val priv = bytes.copyOfRange(32, 64)
        val storedCrc = ((bytes[64].toInt() and 0xFF) shl 24) or
            ((bytes[65].toInt() and 0xFF) shl 16) or
            ((bytes[66].toInt() and 0xFF) shl 8) or
            (bytes[67].toInt() and 0xFF)
        val actualCrc = java.util.zip.CRC32().apply { update(bytes, 0, 64) }.value.toInt()
        require(actualCrc == storedCrc) { "备份码校验失败：CRC32 不匹配，请检查是否有字符抄错" }
        val derivedPub = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        require(derivedPub.contentEquals(pub)) { "备份码校验失败：公钥与私钥不一致" }
        return Identity(pub, priv)
    }

    private fun decodeBackupV04(bytes: ByteArray): Identity {
        // v0.4 had no CRC; only the pub-derived-from-priv check exists.
        val pub = bytes.copyOfRange(0, 32)
        val priv = bytes.copyOfRange(32, 64)
        val derivedPub = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        require(derivedPub.contentEquals(pub)) { "备份码校验失败：公钥与私钥不一致" }
        return Identity(pub, priv)
    }

    /**
     * Restores the identity from a backup string and persists it to settings.
     * Returns only the public key + fingerprint payload; the in-memory private-key
     * copy from the backup string is wiped right after the keystore-backed save.
     * Callers who need the private key for an immediate ECDH should load it via
     * [WentuyiSettings.loadIdentity] (which re-reads from the wrapped store).
     */
    fun restoreIdentityFromBackup(context: Context, backup: String): Identity {
        val identity = decodeBackup(backup)
        // Only a *change* of identity invalidates sessions — restoring the same identity
        // (e.g. onto a new device) must keep working. Compare before overwriting.
        val prevPub = runCatching { WentuyiSettings.loadIdentity(context)?.first }.getOrNull()
        val identityChanged = prevPub == null || !prevPub.contentEquals(identity.publicKey)
        try {
            WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
            if (identityChanged) onIdentityChanged(context)
            // The Identity instance we hand back keeps publicKey but not privateKey;
            // callers should reload from settings if they need it.
            return Identity(identity.publicKey, ByteArray(0))
        } finally {
            CryptoUtils.wipe(identity.privateKey)
        }
    }

    // ─── QR transport for the public key ──────────────────────────────────────

    fun encodeIdentityForQr(name: String, publicKey: ByteArray): String {
        val safeName = name.trim().take(40).replace('|', '/').ifEmpty { "未命名" }
        val b64 = Base64.encodeToString(publicKey, Base64.NO_WRAP or Base64.URL_SAFE)
        return "$QR_PREFIX|$safeName|$b64"
    }

    /** Returns `(name, publicKey)` or throws if [text] is not a valid identity QR. */
    fun decodeIdentityFromQr(text: String): Pair<String, ByteArray> {
        val trimmed = text.trim()
        require(trimmed.startsWith("$QR_PREFIX|")) { "不是文图易身份码" }
        val parts = trimmed.split("|", limit = 3)
        require(parts.size == 3) { "身份码格式不完整" }
        val name = parts[1]
        val key = try {
            Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("身份码内容损坏")
        }
        require(key.size == 32) { "身份码公钥长度异常" }
        return name to key
    }

    // ─── Contact storage (JSON in SharedPreferences) ──────────────────────────

    fun listContacts(context: Context): List<Contact> {
        val arr = JSONArray(WentuyiSettings.getContactsJson(context))
        val out = ArrayList<Contact>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "未命名")
            val keyB64 = obj.optString("publicKey", "")
            if (keyB64.isEmpty()) continue
            val key = try {
                Base64.decode(keyB64, Base64.NO_WRAP or Base64.URL_SAFE)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (key.size == 32) {
                out += Contact(name, key, verified = obj.optBoolean("verified", false))
            }
        }
        return out
    }

    fun saveContact(context: Context, contact: Contact) {
        val existing = listContacts(context).filterNot { it.publicKey.contentEquals(contact.publicKey) }
        val arr = JSONArray()
        for (c in existing + contact) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    fun removeContact(context: Context, fingerprint: String) {
        val remaining = listContacts(context).filterNot { it.fingerprint == fingerprint }
        // Drop any ratchet session too — re-adding the contact should re-bootstrap fresh.
        WentuyiSettings.clearRatchet(context, fingerprint)
        val arr = JSONArray()
        for (c in remaining) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    /** Toggles the verified flag for the contact with [fingerprint]. */
    fun setContactVerified(context: Context, fingerprint: String, verified: Boolean) {
        val updated = listContacts(context).map {
            if (it.fingerprint == fingerprint) it.copy(verified = verified) else it
        }
        val arr = JSONArray()
        for (c in updated) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    private fun Contact.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP or Base64.URL_SAFE))
        if (verified) put("verified", true)
    }

    fun findContact(context: Context, fingerprint: String): Contact? =
        listContacts(context).firstOrNull { it.fingerprint == fingerprint }

    /**
     * Drops any contact whose public key is a low-order point (i.e. ECDH against our
     * identity would return all-zero or BC's IllegalStateException). Pre-v0.5 added
     * contacts before validating; the upgrade to v0.5+ left those "poisoned" rows in
     * the JSON. Run this on every contacts-screen show to migrate forward silently.
     *
     * Returns the count of removed contacts (for surfacing a Toast if non-zero).
     */
    fun pruneInvalidContacts(context: Context): Int {
        val identity = loadIdentity(context) ?: return 0  // no identity → no way to test
        val current = listContacts(context)
        val valid = current.filter { contact ->
            if (contact.publicKey.size != 32) return@filter false
            val ecdh = runCatching { ecdh(identity.privateKey, contact.publicKey) }
            val ok = ecdh.isSuccess
            ecdh.getOrNull()?.let { CryptoUtils.wipe(it) }
            ok
        }
        if (valid.size != current.size) {
            val arr = JSONArray()
            for (c in valid) {
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("publicKey", Base64.encodeToString(c.publicKey, Base64.NO_WRAP or Base64.URL_SAFE))
                    if (c.verified) put("verified", true)
                })
            }
            WentuyiSettings.setContactsJson(context, arr.toString())
        }
        return current.size - valid.size
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }
}
