package com.wentuyi.app

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM envelope codec for 文图易.
 *
 * v3 (current, "WTY3:") — Argon2id KDF, AAD-bound header.
 *   Header (31 bytes, used as GCM AAD):
 *     [0]      version = 0x03
 *     [1]      type    (1=text, 2=image, 3=image-page, 4=image-chunk)
 *     [2]      key mode (0=Argon2id passphrase, 1=HKDF-SHA256 over 32-byte session key)
 *     [3..18]  salt    (16 bytes; used by Argon2id OR HKDF)
 *     [19..30] iv      (12 bytes)
 *   Then: AES-256-GCM ciphertext (with 16-byte tag).
 *
 * v2 ("WTY2:") and v1 ("WTY1:") — legacy PBKDF2 path; decryption supported for migration.
 */
object SecurePayloadCodec {
    const val PREFIX_V1 = "WTY1:"
    const val PREFIX_V2 = "WTY2:"
    const val PREFIX_V3 = "WTY3:"
    const val PREFIX_V4 = "WTY4:"

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

    // v4 default Argon2id (m=64 MiB, t=4, p=1). Written into the header so future tuning
    // stays backward-compatible — old ciphertext carries its own params.
    const val ARGON_MEM_KB_DEFAULT = 64 * 1024
    const val ARGON_ITER_DEFAULT = 4
    const val ARGON_PAR_DEFAULT = 1
    // Clamp applied to attacker-supplied header params on decrypt: too-high → OOM/DoS,
    // too-low → KDF downgrade. Independent of the defaults above.
    private const val ARGON_MEM_KB_MIN = 8 * 1024
    private const val ARGON_MEM_KB_MAX = 256 * 1024
    private const val ARGON_ITER_MIN = 1
    private const val ARGON_ITER_MAX = 10
    private const val ARGON_PAR_MIN = 1
    private const val ARGON_PAR_MAX = 4
    // v4 header: ver(1) type(1) keymode(1) argonMemKB(4) argonIter(1) argonPar(1) salt(16) iv(12)
    private const val V4_SALT_OFFSET = 9
    private const val V4_IV_OFFSET = V4_SALT_OFFSET + SALT_BYTES
    private const val V4_HEADER_LEN = V4_IV_OFFSET + IV_BYTES

    private val IMAGE_PAGE_MAGIC = "WTYIPG1".toByteArray(StandardCharsets.US_ASCII)
    private val IMAGE_CHUNK_MAGIC = "WTYICH1".toByteArray(StandardCharsets.US_ASCII)
    private val SESSION_HKDF_INFO = "WTY3-session-v1".toByteArray(StandardCharsets.US_ASCII)

    // ─── Encryption (v3) ──────────────────────────────────────────────────────

    @JvmStatic
    fun encryptTextToPayload(plainText: String, passphrase: String): String =
        encryptBytes(TYPE_TEXT, plainText.toByteArray(StandardCharsets.UTF_8),
            passphrase = passphrase)

    @JvmStatic
    fun encryptImageToPayload(imageBytes: ByteArray, passphrase: String): String {
        require(imageBytes.isNotEmpty()) { "图片为空" }
        return encryptBytes(TYPE_IMAGE, imageBytes, passphrase = passphrase)
    }

    @JvmStatic
    fun encryptImagePageToPayload(
        imageBytes: ByteArray, pageNumber: Int, pageTotal: Int, passphrase: String
    ): String {
        require(imageBytes.isNotEmpty()) { "图片为空" }
        require(pageNumber in 1..pageTotal && pageTotal >= 1) { "页码无效" }
        return encryptBytes(
            TYPE_IMAGE_PAGE,
            packImagePage(imageBytes, pageNumber, pageTotal),
            passphrase = passphrase
        )
    }

    @JvmStatic
    fun encryptImageChunkToPayload(
        imageBytes: ByteArray, chunkNumber: Int, chunkTotal: Int,
        totalBytes: Int, passphrase: String
    ): String {
        require(imageBytes.isNotEmpty()) { "图片分片为空" }
        require(chunkNumber in 1..chunkTotal && chunkTotal >= 1 && totalBytes > 0) {
            "图片分片页码无效"
        }
        return encryptBytes(
            TYPE_IMAGE_CHUNK,
            packImageChunk(imageBytes, chunkNumber, chunkTotal, totalBytes),
            passphrase = passphrase
        )
    }

    /** Encrypt with a pre-shared 32-byte session key (e.g. from X25519 + HKDF). */
    fun encryptTextWithSessionKey(plainText: String, sessionKey: ByteArray): String =
        encryptBytes(TYPE_TEXT, plainText.toByteArray(StandardCharsets.UTF_8),
            sessionKey = sessionKey)

    fun encryptImageWithSessionKey(imageBytes: ByteArray, sessionKey: ByteArray): String {
        require(imageBytes.isNotEmpty()) { "图片为空" }
        return encryptBytes(TYPE_IMAGE, imageBytes, sessionKey = sessionKey)
    }

    private fun encryptBytes(
        type: Int,
        plain: ByteArray,
        passphrase: String? = null,
        sessionKey: ByteArray? = null,
    ): String {
        val (mode, key) = when {
            sessionKey != null -> {
                require(sessionKey.size == KEY_BYTES) { "会话密钥长度异常" }
                KEY_MODE_SESSION_KEY to sessionKey
            }
            passphrase != null -> {
                requirePassphrase(passphrase)
                KEY_MODE_PASSPHRASE to passphrase.toByteArray(StandardCharsets.UTF_8)
            }
            else -> throw IllegalArgumentException("缺少密钥来源")
        }
        val salt = CryptoUtils.randomBytes(SALT_BYTES)
        val iv = CryptoUtils.randomBytes(IV_BYTES)
        // Argon2 params are meaningful only for passphrase mode; session mode (HKDF)
        // zeroes them. The fields are still AAD-bound either way.
        val memKb = if (mode == KEY_MODE_PASSPHRASE) ARGON_MEM_KB_DEFAULT else 0
        val iter = if (mode == KEY_MODE_PASSPHRASE) ARGON_ITER_DEFAULT else 0
        val par = if (mode == KEY_MODE_PASSPHRASE) ARGON_PAR_DEFAULT else 0

        val aesKey = when (mode) {
            KEY_MODE_PASSPHRASE -> CryptoUtils.argon2id(key, salt, KEY_BYTES, memKb, iter, par)
            else -> CryptoUtils.hkdfSha256(key, salt, SESSION_HKDF_INFO, KEY_BYTES)
        }
        try {
            val header = ByteArray(V4_HEADER_LEN).apply {
                this[0] = 0x04
                this[1] = type.toByte()
                this[2] = mode
                writeInt(this, 3, memKb)
                this[7] = iter.toByte()
                this[8] = par.toByte()
                System.arraycopy(salt, 0, this, V4_SALT_OFFSET, SALT_BYTES)
                System.arraycopy(iv, 0, this, V4_IV_OFFSET, IV_BYTES)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plain)

            val packed = ByteArray(header.size + ciphertext.size)
            System.arraycopy(header, 0, packed, 0, header.size)
            System.arraycopy(ciphertext, 0, packed, header.size, ciphertext.size)
            return PREFIX_V4 + Base64.encodeToString(packed, Base64.NO_WRAP)
        } finally {
            CryptoUtils.wipe(aesKey)
            if (mode == KEY_MODE_PASSPHRASE) CryptoUtils.wipe(key)
        }
    }

    // ─── Decryption (v3 + legacy v2/v1) ───────────────────────────────────────

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun decryptPayload(payload: String, passphrase: String): String {
        val decrypted = decryptEnvelope(payload, passphrase)
        if (decrypted.type != TYPE_TEXT) throw GeneralSecurityException("加密内容是图片")
        return String(decrypted.data, StandardCharsets.UTF_8)
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun decryptEnvelope(payload: String?, passphrase: String): DecryptedPayload {
        requirePassphrase(passphrase)
        if (payload == null) throw GeneralSecurityException("不是文图易加密内容")
        return when {
            payload.startsWith(PREFIX_V4) -> decryptV4(payload, passphrase = passphrase)
            payload.startsWith(PREFIX_V3) -> decryptV3(payload, passphrase = passphrase)
            payload.startsWith(PREFIX_V2) -> decryptV2(payload, passphrase)
            payload.startsWith(PREFIX_V1) -> DecryptedPayload(
                TYPE_TEXT,
                decryptV1Text(payload, passphrase).toByteArray(StandardCharsets.UTF_8)
            )
            else -> throw GeneralSecurityException("不是文图易加密内容")
        }
    }

    /** Decrypt with a pre-shared 32-byte session key (X25519 path). */
    @Throws(GeneralSecurityException::class)
    fun decryptEnvelopeWithSessionKey(payload: String?, sessionKey: ByteArray): DecryptedPayload {
        require(sessionKey.size == KEY_BYTES) { "会话密钥长度异常" }
        if (payload == null) throw GeneralSecurityException("不是文图易加密内容")
        return when {
            payload.startsWith(PREFIX_V4) -> decryptV4(payload, sessionKey = sessionKey)
            payload.startsWith(PREFIX_V3) -> decryptV3(payload, sessionKey = sessionKey)
            else -> throw GeneralSecurityException("会话密钥仅支持 WTY3/WTY4 格式")
        }
    }

    /** Wraps already-decrypted plaintext (e.g. from the Double Ratchet) as a TEXT payload. */
    @JvmStatic
    fun textPayload(data: ByteArray): DecryptedPayload = DecryptedPayload(TYPE_TEXT, data)

    @JvmStatic
    fun isPayload(payload: String?): Boolean = payload != null && (
        payload.startsWith(PREFIX_V1) ||
            payload.startsWith(PREFIX_V2) ||
            payload.startsWith(PREFIX_V3) ||
            payload.startsWith(PREFIX_V4) ||
            payload.startsWith(DoubleRatchet.PREFIX_V5)  // ratchet messages travel as QR too
        )

    /**
     * Returns the key-mode byte from a v3/v4 envelope without decrypting, or null if
     * [payload] isn't a v3/v4 envelope or is too short. The key-mode byte is at offset 2
     * in both versions. Used by the receiver to choose between the shared-passphrase and
     * per-contact session-key decrypt paths.
     */
    fun peekKeyMode(payload: String?): Byte? {
        val prefix = when {
            payload == null -> return null
            payload.startsWith(PREFIX_V4) -> PREFIX_V4
            payload.startsWith(PREFIX_V3) -> PREFIX_V3
            else -> return null
        }
        val packed = try {
            Base64.decode(payload.substring(prefix.length), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) { return null }
        if (packed.size < 3) return null
        return packed[2]
    }

    private fun decryptV4(
        payload: String,
        passphrase: String? = null,
        sessionKey: ByteArray? = null,
    ): DecryptedPayload {
        val packed = Base64.decode(payload.substring(PREFIX_V4.length), Base64.NO_WRAP)
        if (packed.size <= V4_HEADER_LEN) throw GeneralSecurityException("加密内容不完整")
        if (packed[0].toInt() and 0xFF != 0x04) throw GeneralSecurityException("加密版本不支持")

        val type = packed[1].toInt() and 0xFF
        if (type !in 1..4) throw GeneralSecurityException("加密类型不支持")
        val mode = packed[2]
        val salt = Arrays.copyOfRange(packed, V4_SALT_OFFSET, V4_SALT_OFFSET + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, V4_IV_OFFSET, V4_IV_OFFSET + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, V4_HEADER_LEN, packed.size)
        val header = Arrays.copyOfRange(packed, 0, V4_HEADER_LEN)

        val aesKey: ByteArray
        var passphraseBytes: ByteArray? = null
        when (mode) {
            KEY_MODE_PASSPHRASE -> {
                if (passphrase == null) throw GeneralSecurityException("缺少密钥")
                val memKb = readInt(packed, 3)
                val iter = packed[7].toInt() and 0xFF
                val par = packed[8].toInt() and 0xFF
                // Clamp attacker-controlled params before feeding Argon2: too high = OOM/DoS,
                // too low = downgrade. The AAD already pins these to the ciphertext.
                if (memKb !in ARGON_MEM_KB_MIN..ARGON_MEM_KB_MAX ||
                    iter !in ARGON_ITER_MIN..ARGON_ITER_MAX ||
                    par !in ARGON_PAR_MIN..ARGON_PAR_MAX) {
                    throw GeneralSecurityException("加密参数超出安全范围")
                }
                passphraseBytes = passphrase.toByteArray(StandardCharsets.UTF_8)
                aesKey = CryptoUtils.argon2id(passphraseBytes, salt, KEY_BYTES, memKb, iter, par)
            }
            KEY_MODE_SESSION_KEY -> {
                if (sessionKey == null) throw GeneralSecurityException("当前密文需要会话密钥")
                aesKey = CryptoUtils.hkdfSha256(sessionKey, salt, SESSION_HKDF_INFO, KEY_BYTES)
            }
            else -> throw GeneralSecurityException("未知密钥模式")
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(ciphertext)
            return when (type) {
                TYPE_IMAGE_PAGE -> unpackImagePage(plain)
                TYPE_IMAGE_CHUNK -> unpackImageChunk(plain)
                else -> DecryptedPayload(type, plain)
            }
        } finally {
            CryptoUtils.wipe(aesKey)
            CryptoUtils.wipe(passphraseBytes)
        }
    }

    private fun decryptV3(
        payload: String,
        passphrase: String? = null,
        sessionKey: ByteArray? = null,
    ): DecryptedPayload {
        val packed = Base64.decode(payload.substring(PREFIX_V3.length), Base64.NO_WRAP)
        if (packed.size <= V3_HEADER_LEN) throw GeneralSecurityException("加密内容不完整")
        if (packed[0].toInt() and 0xFF != 0x03) throw GeneralSecurityException("加密版本不支持")

        val type = packed[1].toInt() and 0xFF
        if (type !in 1..4) throw GeneralSecurityException("加密类型不支持")
        val mode = packed[2]
        val salt = Arrays.copyOfRange(packed, 3, 3 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 3 + SALT_BYTES, 3 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, V3_HEADER_LEN, packed.size)
        val header = Arrays.copyOfRange(packed, 0, V3_HEADER_LEN)

        val aesKey: ByteArray
        var passphraseBytes: ByteArray? = null
        when (mode) {
            KEY_MODE_PASSPHRASE -> {
                if (passphrase == null) throw GeneralSecurityException("缺少密钥")
                passphraseBytes = passphrase.toByteArray(StandardCharsets.UTF_8)
                aesKey = CryptoUtils.argon2id(passphraseBytes, salt, KEY_BYTES)
            }
            KEY_MODE_SESSION_KEY -> {
                if (sessionKey == null) throw GeneralSecurityException("当前密文需要会话密钥")
                aesKey = CryptoUtils.hkdfSha256(sessionKey, salt, SESSION_HKDF_INFO, KEY_BYTES)
            }
            else -> throw GeneralSecurityException("未知密钥模式")
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(ciphertext)
            return when (type) {
                TYPE_IMAGE_PAGE -> unpackImagePage(plain)
                TYPE_IMAGE_CHUNK -> unpackImageChunk(plain)
                else -> DecryptedPayload(type, plain)
            }
        } finally {
            CryptoUtils.wipe(aesKey)
            CryptoUtils.wipe(passphraseBytes)
        }
    }

    // NOTE (legacy limitation): v2/v1 predate the AAD-bound header, so the `type` byte
    // here is NOT authenticated — only the ciphertext is GCM-protected. An attacker can
    // flip text↔image on an existing v2/v1 blob to cause type-confusion/DoS (it cannot
    // forge or recover plaintext). This is unfixable without breaking already-emitted v2
    // ciphertext; v3 closes it by binding version/type/key-mode/salt/IV into the AAD.
    // These paths exist only for one-way migration of old messages.
    private fun decryptV2(payload: String, passphrase: String): DecryptedPayload {
        val packed = Base64.decode(payload.substring(PREFIX_V2.length), Base64.NO_WRAP)
        if (packed.size <= 2 + SALT_BYTES + IV_BYTES) throw GeneralSecurityException("加密内容不完整")
        if (packed[0].toInt() and 0xFF != 2) throw GeneralSecurityException("加密版本不支持")
        val type = packed[1].toInt() and 0xFF
        if (type !in 1..4) throw GeneralSecurityException("加密类型不支持")

        val salt = Arrays.copyOfRange(packed, 2, 2 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 2 + SALT_BYTES, 2 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, 2 + SALT_BYTES + IV_BYTES, packed.size)

        val key = pbkdf2(passphrase, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(ciphertext)
            return when (type) {
                TYPE_IMAGE_PAGE -> unpackImagePage(plain)
                TYPE_IMAGE_CHUNK -> unpackImageChunk(plain)
                else -> DecryptedPayload(type, plain)
            }
        } finally {
            // SecretKeySpec wraps the byte[] internally; nothing extra to wipe.
        }
    }

    private fun decryptV1Text(payload: String, passphrase: String): String {
        val packed = Base64.decode(payload.substring(PREFIX_V1.length), Base64.NO_WRAP)
        if (packed.size <= 1 + SALT_BYTES + IV_BYTES) throw GeneralSecurityException("加密内容不完整")
        if (packed[0].toInt() and 0xFF != 1) throw GeneralSecurityException("加密版本不支持")

        val salt = Arrays.copyOfRange(packed, 1, 1 + SALT_BYTES)
        val iv = Arrays.copyOfRange(packed, 1 + SALT_BYTES, 1 + SALT_BYTES + IV_BYTES)
        val ciphertext = Arrays.copyOfRange(packed, 1 + SALT_BYTES + IV_BYTES, packed.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, pbkdf2(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun pbkdf2(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS_V2, KEY_BYTES * 8)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (ignored: NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // ─── DecryptedPayload + packers ───────────────────────────────────────────

    class DecryptedPayload internal constructor(
        @JvmField val type: Int,
        @JvmField val data: ByteArray,
        @JvmField val pageNumber: Int = 0,
        @JvmField val pageTotal: Int = 0,
        @JvmField val totalBytes: Int = 0,
    ) {
        fun isText() = type == TYPE_TEXT
        fun isImage() = type == TYPE_IMAGE || type == TYPE_IMAGE_PAGE
        fun isImagePage() = type == TYPE_IMAGE_PAGE
        fun isImageChunk() = type == TYPE_IMAGE_CHUNK
        fun text(): String = String(data, StandardCharsets.UTF_8)
    }

    private fun packImagePage(imageBytes: ByteArray, pageNumber: Int, pageTotal: Int): ByteArray {
        val out = ByteArray(IMAGE_PAGE_MAGIC.size + 8 + imageBytes.size)
        System.arraycopy(IMAGE_PAGE_MAGIC, 0, out, 0, IMAGE_PAGE_MAGIC.size)
        writeInt(out, IMAGE_PAGE_MAGIC.size, pageNumber)
        writeInt(out, IMAGE_PAGE_MAGIC.size + 4, pageTotal)
        System.arraycopy(imageBytes, 0, out, IMAGE_PAGE_MAGIC.size + 8, imageBytes.size)
        return out
    }

    private fun packImageChunk(
        imageBytes: ByteArray, chunkNumber: Int, chunkTotal: Int, totalBytes: Int
    ): ByteArray {
        val out = ByteArray(IMAGE_CHUNK_MAGIC.size + 12 + imageBytes.size)
        System.arraycopy(IMAGE_CHUNK_MAGIC, 0, out, 0, IMAGE_CHUNK_MAGIC.size)
        writeInt(out, IMAGE_CHUNK_MAGIC.size, chunkNumber)
        writeInt(out, IMAGE_CHUNK_MAGIC.size + 4, chunkTotal)
        writeInt(out, IMAGE_CHUNK_MAGIC.size + 8, totalBytes)
        System.arraycopy(imageBytes, 0, out, IMAGE_CHUNK_MAGIC.size + 12, imageBytes.size)
        return out
    }

    private fun unpackImagePage(packed: ByteArray): DecryptedPayload {
        val headerLen = IMAGE_PAGE_MAGIC.size + 8
        if (packed.size <= headerLen) throw GeneralSecurityException("分页图片内容不完整")
        for (i in IMAGE_PAGE_MAGIC.indices)
            if (packed[i] != IMAGE_PAGE_MAGIC[i]) throw GeneralSecurityException("分页图片格式不支持")
        val pageNumber = readInt(packed, IMAGE_PAGE_MAGIC.size)
        val pageTotal = readInt(packed, IMAGE_PAGE_MAGIC.size + 4)
        if (pageNumber !in 1..pageTotal || pageTotal < 1) throw GeneralSecurityException("分页图片页码异常")
        return DecryptedPayload(
            TYPE_IMAGE_PAGE,
            Arrays.copyOfRange(packed, headerLen, packed.size),
            pageNumber, pageTotal
        )
    }

    private fun unpackImageChunk(packed: ByteArray): DecryptedPayload {
        val headerLen = IMAGE_CHUNK_MAGIC.size + 12
        if (packed.size <= headerLen) throw GeneralSecurityException("图片分片内容不完整")
        for (i in IMAGE_CHUNK_MAGIC.indices)
            if (packed[i] != IMAGE_CHUNK_MAGIC[i]) throw GeneralSecurityException("图片分片格式不支持")
        val chunkNumber = readInt(packed, IMAGE_CHUNK_MAGIC.size)
        val chunkTotal = readInt(packed, IMAGE_CHUNK_MAGIC.size + 4)
        val totalBytes = readInt(packed, IMAGE_CHUNK_MAGIC.size + 8)
        if (chunkNumber !in 1..chunkTotal || chunkTotal < 1 || totalBytes <= 0)
            throw GeneralSecurityException("图片分片页码异常")
        return DecryptedPayload(
            TYPE_IMAGE_CHUNK,
            Arrays.copyOfRange(packed, headerLen, packed.size),
            chunkNumber, chunkTotal, totalBytes
        )
    }

    private fun requirePassphrase(passphrase: String?) {
        require(!passphrase.isNullOrBlank()) { "密钥不能为空" }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
}
