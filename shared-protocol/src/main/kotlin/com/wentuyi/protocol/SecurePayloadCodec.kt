package com.wentuyi.protocol

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurePayloadCodec {
    const val PREFIX_V1 = "WTY1:"
    const val PREFIX_V2 = "WTY2:"
    const val PREFIX_V3 = "WTY3:"

    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 2
    const val TYPE_IMAGE_PAGE = 3
    const val TYPE_IMAGE_CHUNK = 4

    const val KEY_MODE_PASSPHRASE: Byte = 0
    const val KEY_MODE_SESSION_KEY: Byte = 1

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS_V2 = 120_000
    private const val V3_HEADER_LEN = 1 + 1 + 1 + SALT_BYTES + IV_BYTES

    private val SESSION_HKDF_INFO = "WTY3-session-v1".toByteArray(StandardCharsets.US_ASCII)

    fun encryptTextToPayload(plainText: String, passphrase: String): String =
        encryptBytes(TYPE_TEXT, plainText.toByteArray(StandardCharsets.UTF_8), passphrase = passphrase)

    fun decryptPayload(payload: String, passphrase: String): String {
        val decrypted = decryptEnvelope(payload, passphrase)
        if (decrypted.type != TYPE_TEXT) throw GeneralSecurityException("encrypted content is not text")
        return decrypted.text()
    }

    fun encryptTextWithSessionKey(plainText: String, sessionKey: ByteArray): String =
        encryptBytes(TYPE_TEXT, plainText.toByteArray(StandardCharsets.UTF_8), sessionKey = sessionKey)

    fun decryptEnvelopeWithSessionKey(payload: String?, sessionKey: ByteArray): DecryptedPayload {
        require(sessionKey.size == KEY_BYTES) { "session key must be 32 bytes" }
        if (payload == null) throw GeneralSecurityException("not a Wentuyi payload")
        if (!payload.startsWith(PREFIX_V3)) throw GeneralSecurityException("session key only supports WTY3")
        return decryptV3(payload, sessionKey = sessionKey)
    }

    fun decryptEnvelope(payload: String?, passphrase: String): DecryptedPayload {
        requirePassphrase(passphrase)
        if (payload == null) throw GeneralSecurityException("not a Wentuyi payload")
        return when {
            payload.startsWith(PREFIX_V3) -> decryptV3(payload, passphrase = passphrase)
            payload.startsWith(PREFIX_V2) -> decryptV2(payload, passphrase)
            payload.startsWith(PREFIX_V1) -> DecryptedPayload(
                TYPE_TEXT,
                decryptV1Text(payload, passphrase).toByteArray(StandardCharsets.UTF_8),
            )
            else -> throw GeneralSecurityException("not a Wentuyi payload")
        }
    }

    fun isPayload(payload: String?): Boolean = payload != null &&
        (payload.startsWith(PREFIX_V1) || payload.startsWith(PREFIX_V2) || payload.startsWith(PREFIX_V3))

    fun peekV3KeyMode(payload: String?): Byte? {
        if (payload == null || !payload.startsWith(PREFIX_V3)) return null
        val packed = try { Encoding.b64Decode(payload.substring(PREFIX_V3.length)) }
            catch (e: IllegalArgumentException) { return null }
        if (packed.size < 3) return null
        return packed[2]
    }

    private fun encryptBytes(
        type: Int,
        plain: ByteArray,
        passphrase: String? = null,
        sessionKey: ByteArray? = null,
    ): String {
        val (mode, key) = when {
            sessionKey != null -> {
                require(sessionKey.size == KEY_BYTES) { "session key must be 32 bytes" }
                KEY_MODE_SESSION_KEY to sessionKey
            }
            passphrase != null -> {
                requirePassphrase(passphrase)
                KEY_MODE_PASSPHRASE to passphrase.toByteArray(StandardCharsets.UTF_8)
            }
            else -> throw IllegalArgumentException("missing key source")
        }
        val salt = CryptoUtils.randomBytes(SALT_BYTES)
        val iv = CryptoUtils.randomBytes(IV_BYTES)
        val aesKey = when (mode) {
            KEY_MODE_PASSPHRASE -> CryptoUtils.argon2id(key, salt, KEY_BYTES)
            else -> CryptoUtils.hkdfSha256(key, salt, SESSION_HKDF_INFO, KEY_BYTES)
        }
        try {
            val header = ByteArray(V3_HEADER_LEN).apply {
                this[0] = 0x03
                this[1] = type.toByte()
                this[2] = mode
                System.arraycopy(salt, 0, this, 3, SALT_BYTES)
                System.arraycopy(iv, 0, this, 3 + SALT_BYTES, IV_BYTES)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plain)
            val packed = ByteArray(header.size + ciphertext.size)
            System.arraycopy(header, 0, packed, 0, header.size)
            System.arraycopy(ciphertext, 0, packed, header.size, ciphertext.size)
            return PREFIX_V3 + Encoding.b64(packed)
        } finally {
            CryptoUtils.wipe(aesKey)
            if (mode == KEY_MODE_PASSPHRASE) CryptoUtils.wipe(key)
        }
    }

    private fun decryptV3(payload: String, passphrase: String? = null, sessionKey: ByteArray? = null): DecryptedPayload {
        val packed = Encoding.b64Decode(payload.substring(PREFIX_V3.length))
        if (packed.size <= V3_HEADER_LEN) throw GeneralSecurityException("payload incomplete")
        if (packed[0].toInt() and 0xFF != 0x03) throw GeneralSecurityException("unsupported version")
        val type = packed[1].toInt() and 0xFF
        if (type !in 1..4) throw GeneralSecurityException("unsupported type")
        val mode = packed[2]
        val salt = Arrays.copyOfRange(packed, 3, 3 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 3 + SALT_BYTES, 3 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, V3_HEADER_LEN, packed.size)
        val header = Arrays.copyOfRange(packed, 0, V3_HEADER_LEN)
        var passphraseBytes: ByteArray? = null
        val aesKey = when (mode) {
            KEY_MODE_PASSPHRASE -> {
                if (passphrase == null) throw GeneralSecurityException("missing passphrase")
                passphraseBytes = passphrase.toByteArray(StandardCharsets.UTF_8)
                CryptoUtils.argon2id(passphraseBytes, salt, KEY_BYTES)
            }
            KEY_MODE_SESSION_KEY -> {
                if (sessionKey == null) throw GeneralSecurityException("session key required")
                CryptoUtils.hkdfSha256(sessionKey, salt, SESSION_HKDF_INFO, KEY_BYTES)
            }
            else -> throw GeneralSecurityException("unknown key mode")
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            return DecryptedPayload(type, cipher.doFinal(ciphertext))
        } finally {
            CryptoUtils.wipe(aesKey)
            CryptoUtils.wipe(passphraseBytes)
        }
    }

    private fun decryptV2(payload: String, passphrase: String): DecryptedPayload {
        val packed = Encoding.b64Decode(payload.substring(PREFIX_V2.length))
        if (packed.size <= 2 + SALT_BYTES + IV_BYTES) throw GeneralSecurityException("payload incomplete")
        if (packed[0].toInt() and 0xFF != 2) throw GeneralSecurityException("unsupported version")
        val type = packed[1].toInt() and 0xFF
        val salt = Arrays.copyOfRange(packed, 2, 2 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 2 + SALT_BYTES, 2 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, 2 + SALT_BYTES + IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, pbkdf2(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return DecryptedPayload(type, cipher.doFinal(ciphertext))
    }

    private fun decryptV1Text(payload: String, passphrase: String): String {
        val packed = Encoding.b64Decode(payload.substring(PREFIX_V1.length))
        if (packed.size <= 1 + SALT_BYTES + IV_BYTES) throw GeneralSecurityException("payload incomplete")
        if (packed[0].toInt() and 0xFF != 1) throw GeneralSecurityException("unsupported version")
        val salt = Arrays.copyOfRange(packed, 1, 1 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 1 + SALT_BYTES, 1 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, 1 + SALT_BYTES + IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, pbkdf2(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun pbkdf2(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS_V2, KEY_BYTES * 8)
        val factory = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            catch (ignored: NoSuchAlgorithmException) { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun requirePassphrase(passphrase: String?) {
        require(!passphrase.isNullOrBlank()) { "passphrase is blank" }
    }

    class DecryptedPayload internal constructor(val type: Int, val data: ByteArray) {
        fun isText(): Boolean = type == TYPE_TEXT
        fun text(): String = String(data, StandardCharsets.UTF_8)
    }
}
