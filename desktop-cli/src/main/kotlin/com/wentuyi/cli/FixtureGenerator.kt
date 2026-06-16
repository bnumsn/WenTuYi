package com.wentuyi.cli

import com.wentuyi.protocol.CryptoUtils
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.SecurePayloadCodec
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Emits the canonical protocol test vectors to stdout as a dependency-free, pipe-delimited
 * text table (so the same file parses with `String.split` on plain JVM and on Android, with
 * no JSON library on any classpath).
 *
 * These are **decrypt** vectors: the frozen `payload` string (with its random salt/IV baked
 * in) is the authoritative artifact. Both `:shared-protocol` and `:app` decode the same file
 * and must reproduce the expected plaintext/type/metadata — that is what catches drift between
 * the two duplicated codec copies until `:app` depends on `:shared-protocol`.
 *
 * Format (see protocol-fixtures/README.md):
 *   # comment
 *   meta|<passphraseBase64>|<sessionKeyHex>
 *   vec|name|prefix|keyMode|type|headerLen|page|total|totalBytes|bodyBase64|payload
 *
 * All variable fields are base64 / hex / ascii, so none can contain the '|' separator.
 *
 * Regenerate with:  ./gradlew :desktop-cli:generateFixtures
 * Salt/IV are random, so regenerating rewrites the payloads — do it intentionally and re-run
 * both test suites afterwards.
 */
object FixtureGenerator {
    private const val PASSPHRASE = "correct horse battery staple 文图易"
    private val SESSION_KEY = ByteArray(32) { it.toByte() } // 00 01 02 ... 1f

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val SESSION_HKDF_INFO = "WTY3-session-v1".toByteArray(StandardCharsets.US_ASCII)
    private val IMAGE_CHUNK_MAGIC = "WTYICH1".toByteArray(StandardCharsets.US_ASCII)

    // A tiny stand-in "image" body (not a real PNG; the codec is content-agnostic).
    private val IMAGE_BYTES = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03, 0x7F, 0x80.toByte(), 0xFF.toByte(),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val out = StringBuilder()
        out.append("# Canonical Wentuyi protocol decrypt vectors — DO NOT hand-edit payloads.\n")
        out.append("# Regenerate: ./gradlew :desktop-cli:generateFixtures  (then re-run both test suites)\n")
        out.append("# Columns: vec|name|prefix|keyMode|type|headerLen|page|total|totalBytes|bodyBase64|payload\n")
        out.append("meta|").append(b64(PASSPHRASE.toByteArray(StandardCharsets.UTF_8)))
            .append('|').append(hex(SESSION_KEY)).append('\n')

        // ── WTY4 (current encrypt output) ──────────────────────────────────────────
        text(out, "v4-passphrase-text", SecurePayloadCodec.encryptTextToPayload("hello 文图易 — passphrase", PASSPHRASE),
            "passphrase", "hello 文图易 — passphrase")
        text(out, "v4-session-text", SecurePayloadCodec.encryptTextWithSessionKey("session 文图易 — hkdf", SESSION_KEY),
            "session", "session 文图易 — hkdf")
        bin(out, "v4-passphrase-image", encryptV4Image(SecurePayloadCodec.TYPE_IMAGE, IMAGE_BYTES, PASSPHRASE),
            "passphrase", SecurePayloadCodec.TYPE_IMAGE, IMAGE_BYTES, 0, 0, 0)
        bin(out, "v4-passphrase-image-page", SecurePayloadCodec.encryptImagePageToPayload(IMAGE_BYTES, 2, 3, PASSPHRASE),
            "passphrase", SecurePayloadCodec.TYPE_IMAGE_PAGE, IMAGE_BYTES, 2, 3, 0)
        bin(out, "v4-session-image-chunk", encryptV4ChunkSession(IMAGE_BYTES, 1, 4, 60, SESSION_KEY),
            "session", SecurePayloadCodec.TYPE_IMAGE_CHUNK, IMAGE_BYTES, 1, 4, 60)

        // ── WTY3 (legacy decrypt path — hand-crafted so the v3 branch stays covered) ─
        text(out, "v3-passphrase-text", craftV3Text("legacy v3 文图易", passphrase = PASSPHRASE),
            "passphrase", "legacy v3 文图易")
        text(out, "v3-session-text", craftV3Text("legacy v3 session 文图易", sessionKey = SESSION_KEY),
            "session", "legacy v3 session 文图易")

        print(out)
    }

    // ── row writers ──────────────────────────────────────────────────────────────────

    private fun text(out: StringBuilder, name: String, payload: String, keyMode: String, plaintext: String) =
        row(out, name, payload, keyMode, SecurePayloadCodec.TYPE_TEXT,
            plaintext.toByteArray(StandardCharsets.UTF_8), 0, 0, 0)

    private fun bin(
        out: StringBuilder, name: String, payload: String, keyMode: String, type: Int, body: ByteArray,
        page: Int, total: Int, totalBytes: Int,
    ) = row(out, name, payload, keyMode, type, body, page, total, totalBytes)

    private fun row(
        out: StringBuilder, name: String, payload: String, keyMode: String, type: Int, body: ByteArray,
        page: Int, total: Int, totalBytes: Int,
    ) {
        val headerLen = if (payload.startsWith(SecurePayloadCodec.PREFIX_V4)) 37 else 31
        out.append("vec|").append(name).append('|').append(payload.substring(0, 5)).append('|')
            .append(keyMode).append('|').append(type).append('|').append(headerLen).append('|')
            .append(page).append('|').append(total).append('|').append(totalBytes).append('|')
            .append(b64(body)).append('|').append(payload).append('\n')
    }

    // ── low-level helpers (mirror the codec so we can craft uncommon variants) ───────

    /** v4 image (type=2) under a passphrase — the public API only exposes text directly. */
    private fun encryptV4Image(type: Int, body: ByteArray, passphrase: String): String {
        val salt = CryptoUtils.randomBytes(SALT_BYTES)
        val iv = CryptoUtils.randomBytes(IV_BYTES)
        val memKb = SecurePayloadCodec.ARGON_MEM_KB_DEFAULT
        val iter = SecurePayloadCodec.ARGON_ITER_DEFAULT
        val par = SecurePayloadCodec.ARGON_PAR_DEFAULT
        val key = CryptoUtils.argon2id(passphrase.toByteArray(StandardCharsets.UTF_8), salt, 32, memKb, iter, par)
        val header = ByteArray(37)
        header[0] = 0x04
        header[1] = type.toByte()
        header[2] = SecurePayloadCodec.KEY_MODE_PASSPHRASE
        writeInt(header, 3, memKb)
        header[7] = iter.toByte()
        header[8] = par.toByte()
        System.arraycopy(salt, 0, header, 9, SALT_BYTES)
        System.arraycopy(iv, 0, header, 9 + SALT_BYTES, IV_BYTES)
        return SecurePayloadCodec.PREFIX_V4 + Encoding.b64(seal(header, iv, key, body))
    }

    /** v4 image chunk (type=4) under a session key — packs the WTYICH1 frame, then HKDF-seals. */
    private fun encryptV4ChunkSession(
        body: ByteArray, chunk: Int, total: Int, totalBytes: Int, sessionKey: ByteArray,
    ): String {
        val frame = ByteArray(IMAGE_CHUNK_MAGIC.size + 12 + body.size)
        System.arraycopy(IMAGE_CHUNK_MAGIC, 0, frame, 0, IMAGE_CHUNK_MAGIC.size)
        writeInt(frame, IMAGE_CHUNK_MAGIC.size, chunk)
        writeInt(frame, IMAGE_CHUNK_MAGIC.size + 4, total)
        writeInt(frame, IMAGE_CHUNK_MAGIC.size + 8, totalBytes)
        System.arraycopy(body, 0, frame, IMAGE_CHUNK_MAGIC.size + 12, body.size)

        val salt = CryptoUtils.randomBytes(SALT_BYTES)
        val iv = CryptoUtils.randomBytes(IV_BYTES)
        val key = CryptoUtils.hkdfSha256(sessionKey, salt, SESSION_HKDF_INFO, 32)
        val header = ByteArray(37)
        header[0] = 0x04
        header[1] = SecurePayloadCodec.TYPE_IMAGE_CHUNK.toByte()
        header[2] = SecurePayloadCodec.KEY_MODE_SESSION_KEY
        // mode=session → argon param fields stay zero (matches the codec).
        System.arraycopy(salt, 0, header, 9, SALT_BYTES)
        System.arraycopy(iv, 0, header, 9 + SALT_BYTES, IV_BYTES)
        return SecurePayloadCodec.PREFIX_V4 + Encoding.b64(seal(header, iv, key, frame))
    }

    /** Hand-craft a legacy v3 text envelope (31-byte header, no argon params field). */
    private fun craftV3Text(text: String, passphrase: String? = null, sessionKey: ByteArray? = null): String {
        val salt = CryptoUtils.randomBytes(SALT_BYTES)
        val iv = CryptoUtils.randomBytes(IV_BYTES)
        val mode: Byte
        val key: ByteArray
        if (sessionKey != null) {
            mode = SecurePayloadCodec.KEY_MODE_SESSION_KEY
            key = CryptoUtils.hkdfSha256(sessionKey, salt, SESSION_HKDF_INFO, 32)
        } else {
            mode = SecurePayloadCodec.KEY_MODE_PASSPHRASE
            // v3 uses the original Argon2id defaults (m=32 MiB, t=3, p=1) — CryptoUtils.argon2id() defaults.
            key = CryptoUtils.argon2id(passphrase!!.toByteArray(StandardCharsets.UTF_8), salt)
        }
        val header = ByteArray(31)
        header[0] = 0x03
        header[1] = SecurePayloadCodec.TYPE_TEXT.toByte()
        header[2] = mode
        System.arraycopy(salt, 0, header, 3, SALT_BYTES)
        System.arraycopy(iv, 0, header, 3 + SALT_BYTES, IV_BYTES)
        return SecurePayloadCodec.PREFIX_V3 + Encoding.b64(seal(header, iv, key, text.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun seal(header: ByteArray, iv: ByteArray, key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(header)
        val ct = cipher.doFinal(plain)
        return header + ct
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun b64(bytes: ByteArray): String = Encoding.b64(bytes)

    private fun hex(bytes: ByteArray): String = buildString {
        for (b in bytes) append("%02x".format(b.toInt() and 0xFF))
    }
}
