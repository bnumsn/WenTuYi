package com.wentuyi.app

import com.wentuyi.protocol.CryptoUtils
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.ProtocolError
import com.wentuyi.protocol.ProtocolException
import com.wentuyi.protocol.SecurePayloadCodec
import com.wentuyi.protocol.KeyExchange as ProtoKE

import android.content.Context
import android.util.Base64
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
 * The full 256-bit [shortAuthString] must be compared through a trusted channel.
 * Short numeric codes are insufficient for non-interactive public-key exchange.
 *
 * Public key is plain X25519 (32 bytes). QR payload uses a "WTYID1|<name>|<base64>"
 * canonical line, easy to ZXing-encode/decode and clearly distinguishable from
 * encrypted-message QR contents.
 */
object KeyExchange {
    const val QR_PREFIX = "WTYID1"
    const val AUTH_VERSION = ProtoKE.AUTH_VERSION

    // The X25519 / SAS / backup / QR crypto now lives once in :shared-protocol; the methods
    // below delegate to it. This object keeps only the Android-specific surface — Identity /
    // Contact value types, Keystore-backed identity persistence, and the SharedPreferences
    // contact store — plus a thin layer that re-localizes protocol errors back to Chinese.
    private fun Identity.toProto() = ProtoKE.Identity(publicKey, privateKey)

    private fun localizedMessage(code: ProtocolError): String = when (code) {
        ProtocolError.LOW_ORDER_KEY -> "不安全的公钥（低阶点）"
        ProtocolError.NOT_A_BACKUP -> "不是文图易身份备份码"
        ProtocolError.BACKUP_CORRUPT -> "备份码内容损坏"
        ProtocolError.BACKUP_LENGTH -> "备份码长度异常"
        ProtocolError.BACKUP_CRC_MISMATCH -> "备份码校验失败：CRC32 不匹配，请检查是否有字符抄错"
        ProtocolError.BACKUP_KEY_MISMATCH -> "备份码校验失败：公钥与私钥不一致"
        ProtocolError.NOT_AN_IDENTITY_QR -> "不是文图易身份码"
        ProtocolError.IDENTITY_QR_INCOMPLETE -> "身份码格式不完整"
        ProtocolError.IDENTITY_QR_CORRUPT -> "身份码内容损坏"
        ProtocolError.IDENTITY_KEY_LENGTH -> "身份码公钥长度异常"
    }

    private fun <T> localized(block: () -> T): T =
        try {
            block()
        } catch (e: ProtocolException) {
            throw IllegalArgumentException(localizedMessage(e.code), e)
        }

    data class Identity(val publicKey: ByteArray, val privateKey: ByteArray) {
        val fingerprint: String get() = Encoding.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))

        override fun equals(other: Any?): Boolean =
            other is Identity && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    data class Contact(
        val name: String,
        val publicKey: ByteArray,
        /**
         * True only after the user has personally compared the complete safety code.
         * Defaults
         * to false at scan time — added contacts are "preliminary" until both sides
         * verify out-of-band. The IME marks unverified targets visually so users
         * aren't lulled into thinking a MITM hasn't slipped between them.
         */
        val verified: Boolean = false,
        val authVersion: Int = if (verified) AUTH_VERSION else 0,
    ) {
        val fingerprint: String get() = Encoding.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))

        // Equality is by publicKey only (name/verified state can drift independently).
        override fun equals(other: Any?): Boolean =
            other is Contact && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    // ─── Identity keypair ─────────────────────────────────────────────────────

    fun getOrCreateIdentity(context: Context): Identity = synchronized(WentuyiSettings.cryptoStateLock) {
        // An unreadable identity requires explicit recovery, never silent replacement.
        WentuyiSettings.loadIdentity(context)
            ?.let { (pub, priv) -> return Identity(pub, priv) }
        val identity = generateIdentity()
        // First generation is a clean break from any contacts/sessions already stored.
        onIdentityChanged(context)
        WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
        return identity
    }

    fun replaceIdentity(context: Context, discardUnreadableContacts: Boolean = false): Identity = synchronized(WentuyiSettings.cryptoStateLock) {
        val identity = generateIdentity()
        // New identity is always a clean break — drop all WTY5 sessions and require
        // re-verification BEFORE saving it. A crash between these durable writes can
        // only lose a session, never reuse an old session with the new identity.
        onIdentityChanged(context, discardUnreadableContacts)
        WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
        return identity
    }

    /**
     * Invalidates everything tied to the old local identity: clears all ratchet sessions
     * (they were rooted in the old identity's ECDH) and marks every contact unverified
     * (the mutual SAS changed, so prior out-of-band verification no longer holds).
     */
    private fun onIdentityChanged(context: Context, discardUnreadableContacts: Boolean = false) {
        WentuyiSettings.clearAllRatchets(context)
        val contacts = try {
            listContacts(context)
        } catch (e: Exception) {
            if (!discardUnreadableContacts) throw e
            // Only the explicit restore/replace flow can discard an unreadable list.
            WentuyiSettings.setContactsJson(context, "[]")
            emptyList()
        }
        val downgraded = contacts.map { it.copy(verified = false, authVersion = 0) }
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
    fun clearCorruptedIdentity(context: Context): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        WentuyiSettings.clearIdentity(context)
    }

    /** Generates a fresh X25519 keypair — used for identities and for ratchet keys. */
    internal fun generateIdentity(): Identity = ProtoKE.generateIdentity().let { Identity(it.publicKey, it.privateKey) }

    // ─── ECDH + session key ───────────────────────────────────────────────────

    /**
     * Computes the 32-byte X25519 shared secret. Identical on both peers. Rejects low-order
     * public keys (all-zero ECDH output, RFC 7748 §6.1) — delegated to :shared-protocol.
     */
    fun ecdh(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray =
        localized { ProtoKE.ecdh(myPrivate, peerPublic) }

    /**
     * Derives a deterministic 32-byte session key for [SecurePayloadCodec]
     * (KEY_MODE_SESSION_KEY). Public keys are sorted lexicographically so both peers
     * derive identical bytes regardless of who's initiating.
     */
    fun deriveSharedSecret(myIdentity: Identity, peerPublic: ByteArray): ByteArray =
        localized { ProtoKE.deriveSharedSecret(myIdentity.toProto(), peerPublic) }

    /**
     * Derives a complete 256-bit safety code for out-of-band verification.
     * Both peers must compare every group, never a truncated prefix/suffix.
     */
    fun shortAuthString(myIdentity: Identity, peerPublic: ByteArray): String =
        localized { ProtoKE.shortAuthString(myIdentity.toProto(), peerPublic) }

    // ─── Identity backup (Base32 string the user can write down) ──────────────

    /**
     * Encodes the local identity as a grouped Base32 string (`WTYB1-XXXX-…`) for paper / a
     * password manager. v0.5 format with trailing CRC32; v0.4 (64-byte) backups still decode.
     * Crypto + format delegated to :shared-protocol.
     */
    fun encodeBackup(identity: Identity): String = ProtoKE.encodeBackup(identity.toProto())

    /** Inverse of [encodeBackup]; localizes protocol failures back to Chinese for the UI. */
    fun decodeBackup(text: String): Identity =
        localized { ProtoKE.decodeBackup(text).let { Identity(it.publicKey, it.privateKey) } }

    /**
     * Restores the identity from a backup string and persists it to settings.
     * Returns only the public key + fingerprint payload; the in-memory private-key
     * copy from the backup string is wiped right after the keystore-backed save.
     * Callers who need the private key for an immediate ECDH should load it via
     * [WentuyiSettings.loadIdentity] (which re-reads from the wrapped store).
     */
    fun restoreIdentityFromBackup(context: Context, backup: String, discardUnreadableContacts: Boolean = false): Identity = synchronized(WentuyiSettings.cryptoStateLock) {
        val identity = decodeBackup(backup)
        // Only a *change* of identity invalidates sessions — restoring the same identity
        // (e.g. onto a new device) must keep working. Compare before overwriting.
        val prevPub = runCatching { WentuyiSettings.loadIdentity(context)?.first }.getOrNull()
        val identityChanged = prevPub == null || !prevPub.contentEquals(identity.publicKey)
        try {
            if (identityChanged) {
                onIdentityChanged(context, discardUnreadableContacts)
            } else if (discardUnreadableContacts && runCatching { listContacts(context) }.isFailure) {
                onIdentityChanged(context, discardUnreadableContacts = true)
            }
            WentuyiSettings.saveIdentity(context, identity.publicKey, identity.privateKey)
            // The Identity instance we hand back keeps publicKey but not privateKey;
            // callers should reload from settings if they need it.
            return Identity(identity.publicKey, ByteArray(0))
        } finally {
            CryptoUtils.wipe(identity.privateKey)
        }
    }

    // ─── QR transport for the public key ──────────────────────────────────────

    fun encodeIdentityForQr(name: String, publicKey: ByteArray): String =
        ProtoKE.encodeIdentityForQr(name, publicKey)

    /** Returns `(name, publicKey)` or throws (localized) if [text] is not a valid identity QR. */
    fun decodeIdentityFromQr(text: String): Pair<String, ByteArray> =
        localized { ProtoKE.decodeIdentityFromQr(text) }

    // ─── Contact storage (JSON in SharedPreferences) ──────────────────────────

    fun listContacts(context: Context): List<Contact> = synchronized(WentuyiSettings.cryptoStateLock) {
        val arr = JSONArray(WentuyiSettings.getContactsJson(context))
        val out = ArrayList<Contact>(arr.length())
        var migrated = false
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
                val claimedVerified = obj.optBoolean("verified", false)
                val version = obj.optInt("authVersion", 0)
                val verified = claimedVerified && version == AUTH_VERSION
                if (claimedVerified && !verified) {
                    obj.remove("verified")
                    obj.remove("authVersion")
                    migrated = true
                }
                out += Contact(name, key, verified = verified, authVersion = if (verified) AUTH_VERSION else 0)
            }
        }
        if (migrated) WentuyiSettings.setContactsJson(context, arr.toString())
        out
    }

    fun saveContact(context: Context, contact: Contact): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        val current = listContacts(context)
        val previous = current.firstOrNull { it.publicKey.contentEquals(contact.publicKey) }
        val updated = if (previous == null) contact else contact.copy(
            verified = previous.verified, authVersion = previous.authVersion)
        val existing = current.filterNot { it.publicKey.contentEquals(contact.publicKey) }
        val arr = JSONArray()
        for (c in existing + updated) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    /** A dialog may outlive an identity/verification edit; rename only the current record. */
    fun renameContact(context: Context, publicKey: ByteArray, name: String): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        val current = listContacts(context)
        check(current.any { it.publicKey.contentEquals(publicKey) }) { "联系人已移除，请重新选择" }
        val arr = JSONArray()
        current.forEach { arr.put((if (it.publicKey.contentEquals(publicKey)) it.copy(name = name) else it).toJson()) }
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    fun removeContact(context: Context, fingerprint: String): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        val remaining = listContacts(context).filterNot { it.fingerprint == fingerprint }
        // Drop any ratchet session too — re-adding the contact should re-bootstrap fresh.
        WentuyiSettings.clearRatchet(context, fingerprint)
        val arr = JSONArray()
        for (c in remaining) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    /** Explicit user recovery from an unreadable contact list; never resets the migration anchor. */
    fun clearContactsAndSessions(context: Context): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        WentuyiSettings.clearAllRatchets(context)
        WentuyiSettings.setContactsJson(context, "[]")
    }

    /** Toggles the verified flag for the contact with [fingerprint]. */
    fun setContactVerified(context: Context, fingerprint: String, verified: Boolean, expectedSafetyCode: String? = null): Unit = synchronized(WentuyiSettings.cryptoStateLock) {
        if (verified && expectedSafetyCode != null) {
            val identity = loadIdentity(context) ?: throw IllegalStateException("请先恢复身份")
            val contact = findContact(context, fingerprint) ?: throw IllegalStateException("联系人已移除")
            check(shortAuthString(identity, contact.publicKey) == expectedSafetyCode) {
                "身份或联系人已变化，请重新核对完整安全码"
            }
        }
        val updated = listContacts(context).map {
            if (it.fingerprint == fingerprint) it.copy(verified = verified, authVersion = if (verified) AUTH_VERSION else 0) else it
        }
        val arr = JSONArray()
        for (c in updated) arr.put(c.toJson())
        WentuyiSettings.setContactsJson(context, arr.toString())
    }

    private fun Contact.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP or Base64.URL_SAFE))
        if (verified && authVersion == AUTH_VERSION) {
            put("verified", true)
            put("authVersion", AUTH_VERSION)
        }
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
    fun pruneInvalidContacts(context: Context): Int = synchronized(WentuyiSettings.cryptoStateLock) {
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
                arr.put(c.toJson())
            }
            WentuyiSettings.setContactsJson(context, arr.toString())
        }
        return current.size - valid.size
    }
}
