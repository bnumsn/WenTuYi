package com.wentuyi.protocol

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.CRC32

object KeyExchange {
    const val QR_PREFIX = "WTYID1"
    private const val SHARED_SECRET_LEN = 32
    private const val BACKUP_PREFIX = "WTYB1"
    private val random = SecureRandom()
    private val HKDF_INFO_SAS = "WTY-SAS-v1".toByteArray(StandardCharsets.US_ASCII)
    private val HKDF_INFO_SESSION = "WTY-session-v1".toByteArray(StandardCharsets.US_ASCII)

    class Identity(val publicKey: ByteArray, val privateKey: ByteArray) {
        val fingerprint: String get() = Encoding.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))

        override fun equals(other: Any?): Boolean = other is Identity && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    class Contact(val name: String, val publicKey: ByteArray, val verified: Boolean = false) {
        val fingerprint: String get() = Encoding.Base32.encode(CryptoUtils.sha256(publicKey).copyOf(8))
    }

    fun generateIdentity(): Identity {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as X25519PrivateKeyParameters).encoded
        val pub = (pair.public as X25519PublicKeyParameters).encoded
        return Identity(pub, priv)
    }

    fun identityFromBase64(publicKey: String, privateKey: String): Identity =
        Identity(Encoding.b64UrlDecode(publicKey), Encoding.b64UrlDecode(privateKey))

    fun ecdh(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray {
        require(myPrivate.size == 32) { "private key must be 32 bytes" }
        require(peerPublic.size == 32) { "peer public key must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPrivate, 0))
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("unsafe low-order public key", e)
        }
        var accumulator = 0
        for (b in out) accumulator = accumulator or (b.toInt() and 0xFF)
        if (accumulator == 0) throw IllegalArgumentException("unsafe low-order public key")
        return out
    }

    fun deriveSharedSecret(myIdentity: Identity, peerPublic: ByteArray): ByteArray {
        val ecdh = ecdh(myIdentity.privateKey, peerPublic)
        try {
            val (low, high) = if (compareBytes(myIdentity.publicKey, peerPublic) <= 0)
                myIdentity.publicKey to peerPublic else peerPublic to myIdentity.publicKey
            val salt = ByteArray(low.size + high.size).apply {
                System.arraycopy(low, 0, this, 0, low.size)
                System.arraycopy(high, 0, this, low.size, high.size)
            }
            return CryptoUtils.hkdfSha256(ecdh, salt, HKDF_INFO_SESSION, SHARED_SECRET_LEN)
        } finally {
            CryptoUtils.wipe(ecdh)
        }
    }

    fun shortAuthString(myIdentity: Identity, peerPublic: ByteArray): String {
        val ecdh = ecdh(myIdentity.privateKey, peerPublic)
        try {
            val (low, high) = if (compareBytes(myIdentity.publicKey, peerPublic) <= 0)
                myIdentity.publicKey to peerPublic else peerPublic to myIdentity.publicKey
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

    fun encodeIdentityForQr(name: String, publicKey: ByteArray): String {
        val safeName = name.trim().take(40).replace('|', '/').ifEmpty { "未命名" }
        return "$QR_PREFIX|$safeName|${Encoding.b64Url(publicKey)}"
    }

    fun decodeIdentityFromQr(text: String): Pair<String, ByteArray> {
        val trimmed = text.trim()
        require(trimmed.startsWith("$QR_PREFIX|")) { "not a Wentuyi identity QR" }
        val parts = trimmed.split("|", limit = 3)
        require(parts.size == 3) { "identity QR incomplete" }
        val key = Encoding.b64UrlDecode(parts[2])
        require(key.size == 32) { "identity public key must be 32 bytes" }
        return parts[1] to key
    }

    fun encodeBackup(identity: Identity): String {
        val packed = ByteArray(68)
        System.arraycopy(identity.publicKey, 0, packed, 0, 32)
        System.arraycopy(identity.privateKey, 0, packed, 32, 32)
        val crc = CRC32().apply { update(packed, 0, 64) }.value.toInt()
        packed[64] = (crc ushr 24).toByte()
        packed[65] = (crc ushr 16).toByte()
        packed[66] = (crc ushr 8).toByte()
        packed[67] = (crc and 0xFF).toByte()
        return BACKUP_PREFIX + "-" + Encoding.Base32.encode(packed).chunked(5).joinToString("-")
    }

    fun decodeBackup(text: String): Identity {
        val cleaned = text.trim().uppercase()
            .replace(Regex("[\\s\\u00A0\\u200B-\\u200D\\uFEFF\\-]"), "")
        require(cleaned.startsWith(BACKUP_PREFIX)) { "not a Wentuyi identity backup" }
        val bytes = Encoding.Base32.decode(cleaned.substring(BACKUP_PREFIX.length))
        return when (bytes.size) {
            68 -> decodeBackupV05(bytes)
            64 -> decodeBackupV04(bytes)
            else -> throw IllegalArgumentException("backup length invalid (${bytes.size} bytes)")
        }
    }

    private fun decodeBackupV05(bytes: ByteArray): Identity {
        val pub = bytes.copyOfRange(0, 32)
        val priv = bytes.copyOfRange(32, 64)
        val storedCrc = ((bytes[64].toInt() and 0xFF) shl 24) or
            ((bytes[65].toInt() and 0xFF) shl 16) or
            ((bytes[66].toInt() and 0xFF) shl 8) or
            (bytes[67].toInt() and 0xFF)
        val actualCrc = CRC32().apply { update(bytes, 0, 64) }.value.toInt()
        require(storedCrc == actualCrc) { "backup CRC mismatch" }
        val derivedPub = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        require(derivedPub.contentEquals(pub)) { "backup public/private key mismatch" }
        return Identity(pub, priv)
    }

    private fun decodeBackupV04(bytes: ByteArray): Identity {
        val pub = bytes.copyOfRange(0, 32)
        val priv = bytes.copyOfRange(32, 64)
        val derivedPub = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
        require(derivedPub.contentEquals(pub)) { "backup public/private key mismatch" }
        return Identity(pub, priv)
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
