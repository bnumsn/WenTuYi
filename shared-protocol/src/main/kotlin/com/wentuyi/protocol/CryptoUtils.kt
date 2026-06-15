package com.wentuyi.protocol

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

object CryptoUtils {
    private val random = SecureRandom()

    const val ARGON2_MEMORY_KB = 32 * 1024
    const val ARGON2_ITERATIONS = 3
    const val ARGON2_PARALLELISM = 1

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        outLen: Int = 32,
        memoryKb: Int = ARGON2_MEMORY_KB,
        iterations: Int = ARGON2_ITERATIONS,
        parallelism: Int = ARGON2_PARALLELISM,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(outLen)
        gen.generateBytes(passphrase, out)
        return out
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(outLen)
        gen.generateBytes(out, 0, outLen)
        return out
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun wipe(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0)
    }
}
