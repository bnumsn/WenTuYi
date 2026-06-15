package com.wentuyi.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the user's shared passphrase (legacy) and their X25519 identity keypair.
 * All long-lived secrets are wrapped by an Android Keystore-resident AES-256-GCM key
 * (alias `wentuyi_passphrase_key`) and stored in private SharedPreferences.
 *
 * Hard-fails on Keystore errors instead of falling back to plaintext (unlike the
 * earlier `KEY_LEGACY_PASSPHRASE` migration path which leaked cleartext on failure).
 */
object WentuyiSettings {
    private const val PREFS = "wentuyi_settings"
    private const val KEY_ENCRYPTED_PASSPHRASE = "passphrase_encrypted"
    private const val KEY_ENCRYPTED_IDENTITY = "identity_encrypted"
    private const val KEY_CONTACTS_JSON = "contacts_json"
    // KS1 was the v2 prefix; v3's wrapper format is identical so we accept both on read.
    // All v3 writes use KS2 to flag "produced by the new code path".
    private const val ENCRYPTED_PREFIX = "KS2:"
    private const val LEGACY_ENCRYPTED_PREFIX = "KS1:"
    private const val DEBUG_DEFAULT_PASSPHRASE = "wentuyi-demo-key"
    private const val MISSING_PASSPHRASE_MESSAGE = "请先在文图易中保存共享密钥"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "wentuyi_passphrase_key"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    // ─── Passphrase (legacy shared-key flow) ──────────────────────────────────

    @JvmStatic
    fun getPassphrase(context: Context): String {
        getStoredPassphrase(context)?.let { return it }
        if (BuildConfig.DEBUG) return DEBUG_DEFAULT_PASSPHRASE
        throw IllegalStateException(MISSING_PASSPHRASE_MESSAGE)
    }

    @JvmStatic
    fun hasSavedPassphrase(context: Context): Boolean = getStoredPassphrase(context) != null

    @JvmStatic
    fun setPassphrase(context: Context, passphrase: String) {
        require(passphrase.isNotBlank()) { "密钥不能为空" }
        try {
            putKeystoreString(prefs(context), KEY_ENCRYPTED_PASSPHRASE, passphrase)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("密钥保存失败", e)
        }
    }

    @JvmStatic
    fun isUsingDefaultPassphrase(context: Context): Boolean =
        BuildConfig.DEBUG && !hasSavedPassphrase(context)

    private fun getStoredPassphrase(context: Context): String? {
        val prefs = prefs(context)
        val encrypted = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null) ?: return null
        val plain = try {
            decryptKeystoreString(encrypted).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // Hard-fail: never expose plaintext fallback. Caller will surface the error.
            throw IllegalStateException("密钥读取失败，请重新保存共享密钥", e)
        }
        // Auto-migrate KS1: → KS2: on the first successful read so subsequent reads
        // skip the legacy-prefix branch entirely. Best-effort; failure is non-fatal.
        if (plain != null && encrypted.startsWith(LEGACY_ENCRYPTED_PREFIX)) {
            runCatching { putKeystoreString(prefs, KEY_ENCRYPTED_PASSPHRASE, plain) }
        }
        return plain
    }

    // ─── X25519 identity keypair ──────────────────────────────────────────────

    /** Persists the local X25519 identity keypair (private key encrypted by Keystore). */
    fun saveIdentity(context: Context, publicKey: ByteArray, privateKey: ByteArray) {
        val packed = ByteArray(publicKey.size + privateKey.size + 1)
        packed[0] = publicKey.size.toByte()
        System.arraycopy(publicKey, 0, packed, 1, publicKey.size)
        System.arraycopy(privateKey, 0, packed, 1 + publicKey.size, privateKey.size)
        val b64 = Base64.encodeToString(packed, Base64.NO_WRAP)
        try {
            putKeystoreString(prefs(context), KEY_ENCRYPTED_IDENTITY, b64)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("身份密钥保存失败", e)
        }
    }

    /** Returns `(publicKey, privateKey)` or null if no identity has been generated. */
    fun loadIdentity(context: Context): Pair<ByteArray, ByteArray>? {
        val encrypted = prefs(context).getString(KEY_ENCRYPTED_IDENTITY, null) ?: return null
        val b64 = try {
            decryptKeystoreString(encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("身份密钥读取失败", e)
        }
        val packed = Base64.decode(b64, Base64.NO_WRAP)
        if (packed.isEmpty()) return null
        val pubLen = packed[0].toInt() and 0xFF
        if (1 + pubLen >= packed.size) throw IllegalStateException("身份密钥数据损坏")
        val pub = packed.copyOfRange(1, 1 + pubLen)
        val priv = packed.copyOfRange(1 + pubLen, packed.size)
        // Identity prefs also benefit from the KS1 → KS2 migration so we don't ship
        // legacy-prefix branches forever.
        if (encrypted.startsWith(LEGACY_ENCRYPTED_PREFIX)) {
            runCatching { saveIdentity(context, pub, priv) }
        }
        return pub to priv
    }

    fun hasIdentity(context: Context): Boolean =
        prefs(context).getString(KEY_ENCRYPTED_IDENTITY, null) != null

    /** Drops the encrypted-identity pref, used by the Keystore-corruption recovery path. */
    fun clearIdentity(context: Context) {
        prefs(context).edit().remove(KEY_ENCRYPTED_IDENTITY).apply()
    }

    // ─── Contacts (peer X25519 public keys) ───────────────────────────────────

    fun getContactsJson(context: Context): String =
        prefs(context).getString(KEY_CONTACTS_JSON, null) ?: "[]"

    fun setContactsJson(context: Context, json: String) {
        prefs(context).edit().putString(KEY_CONTACTS_JSON, json).apply()
    }

    // ─── Per-contact Double Ratchet state (Keystore-wrapped, keyed by fingerprint) ──

    private fun ratchetKey(fingerprint: String) = "ratchet_$fingerprint"

    /** Returns the serialized ratchet state for [fingerprint], or null if none/corrupt. */
    fun loadRatchet(context: Context, fingerprint: String): String? {
        val encrypted = prefs(context).getString(ratchetKey(fingerprint), null) ?: return null
        return try {
            decryptKeystoreString(encrypted)
        } catch (e: Exception) {
            // Corrupt ratchet state is unrecoverable — drop it so a fresh session re-bootstraps.
            prefs(context).edit().remove(ratchetKey(fingerprint)).apply()
            null
        }
    }

    fun saveRatchet(context: Context, fingerprint: String, json: String) {
        try {
            // synchronous = true: state must hit disk before the produced ciphertext is sent.
            putKeystoreString(prefs(context), ratchetKey(fingerprint), json, synchronous = true)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("棘轮状态保存失败", e)
        }
    }

    fun clearRatchet(context: Context, fingerprint: String) {
        prefs(context).edit().remove(ratchetKey(fingerprint)).apply()
    }

    /**
     * Subscribes [onChanged] to contact-list mutations (rename / delete / add). Used
     * by the IME so its [cachedContacts] doesn't go stale while the user is editing
     * contacts in [KeyManagementActivity] without switching input fields first.
     *
     * Returns the listener instance — callers must unregister via
     * [stopWatchingContacts] in `onDestroy` to avoid leaks.
     */
    fun watchContactsChanges(
        context: Context,
        onChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == KEY_CONTACTS_JSON) onChanged()
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopWatchingContacts(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Throws(GeneralSecurityException::class)
    private fun putKeystoreString(
        prefs: SharedPreferences,
        key: String,
        value: String,
        synchronous: Boolean = false,
    ) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + cipherText.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(cipherText, 0, packed, 1 + iv.size, cipherText.size)
        val editor = prefs.edit()
            .putString(key, ENCRYPTED_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP))
        // Ratchet state MUST be durable before the ciphertext it produced is sent — an
        // async apply() that loses the write across a crash would re-derive the same
        // message key/nonce on restart (AES-GCM nonce reuse). commit() writes synchronously.
        if (synchronous) editor.commit() else editor.apply()
    }

    @Throws(GeneralSecurityException::class)
    private fun decryptKeystoreString(encrypted: String): String {
        val payload = when {
            encrypted.startsWith(ENCRYPTED_PREFIX) -> encrypted.substring(ENCRYPTED_PREFIX.length)
            encrypted.startsWith(LEGACY_ENCRYPTED_PREFIX) -> encrypted.substring(LEGACY_ENCRYPTED_PREFIX.length)
            else -> throw GeneralSecurityException("密钥格式不支持")
        }
        val packed = Base64.decode(payload, Base64.NO_WRAP)
        if (packed.size < 2) throw GeneralSecurityException("密钥数据不完整")
        val ivLen = packed[0].toInt() and 0xFF
        if (ivLen <= 0 || 1 + ivLen >= packed.size)
            throw GeneralSecurityException("密钥数据不完整")
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val cipherText = packed.copyOfRange(1 + ivLen, packed.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }

    @Throws(GeneralSecurityException::class)
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }
}
