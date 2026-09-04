package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * Runs the repo-level canonical vectors (`protocol-fixtures/vectors.txt`, shipped into the test
 * APK assets) through the codec the app actually ships — now `com.wentuyi.protocol.SecurePayloadCodec`
 * after the P0.2 migration. This is the on-device proof that the shared codec decrypts every frozen
 * payload and rejects representative header tampering under the real Android runtime (real
 * BouncyCastle Argon2 + AndroidKeyStore-free GCM), complementing :shared-protocol's exhaustive
 * JVM VectorContractTest. The on-device tamper test deliberately uses session-key vectors for each
 * envelope layout so it can verify Android's GCM AAD binding without spending Argon2 work on every
 * tamper mutation. Passphrase vectors are still decrypted once by everyVectorDecryptsToItsExpectedPlaintext().
 */
@RunWith(AndroidJUnit4::class)
class VectorContractTest {

    @Test fun everyVectorDecryptsToItsExpectedPlaintext() {
        val file = loadVectors()
        assertTrue("no vectors loaded", file.vectors.isNotEmpty())
        for (v in file.vectors) {
            val decrypted = decrypt(file, v)
            assertEquals("${v.name}: type", v.type, decrypted.type)
            assertArrayEquals("${v.name}: body", v.body, decrypted.data)
            if (v.type == SecurePayloadCodec.TYPE_IMAGE_PAGE) {
                assertEquals("${v.name}: pageNumber", v.page, decrypted.pageNumber)
                assertEquals("${v.name}: pageTotal", v.total, decrypted.pageTotal)
            }
            if (v.type == SecurePayloadCodec.TYPE_IMAGE_CHUNK) {
                assertEquals("${v.name}: chunkNumber", v.page, decrypted.pageNumber)
                assertEquals("${v.name}: chunkTotal", v.total, decrypted.pageTotal)
                assertEquals("${v.name}: totalBytes", v.totalBytes, decrypted.totalBytes)
            }
        }
    }

    @Test fun representativeSessionHeaderFieldTamperFailsDecryption() {
        val file = loadVectors()
        for (v in representativeTamperVectors(file)) {
            val prefix = v.payload.substring(0, 5)
            val packed = Base64.decode(v.payload.substring(prefix.length), Base64.NO_WRAP)
            for (i in representativeHeaderOffsets(v)) {
                val tampered = packed.copyOf()
                tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
                val mutated = prefix + Base64.encodeToString(tampered, Base64.NO_WRAP)
                try {
                    decrypt(file, v.copy(payload = mutated))
                    fail("${v.name}: header byte $i tamper was not rejected")
                } catch (expected: Exception) {
                    // decryption must reject any header mutation (GCM AAD binding)
                }
            }
        }
    }

    private fun representativeTamperVectors(file: VectorFile): List<VectorFile.Vector> =
        listOf(
            requireNotNull(file.vectors.firstOrNull {
                it.keyMode == "session" && it.payload.startsWith(SecurePayloadCodec.PREFIX_V4)
            }) { "missing WTY4 session vector" },
            requireNotNull(file.vectors.firstOrNull {
                it.keyMode == "session" && it.payload.startsWith(SecurePayloadCodec.PREFIX_V3)
            }) { "missing WTY3 session vector" },
        )

    private fun representativeHeaderOffsets(v: VectorFile.Vector): List<Int> =
        when {
            v.payload.startsWith(SecurePayloadCodec.PREFIX_V4) -> listOf(
                0,  // version
                1,  // type
                2,  // key mode
                3,  // Argon memory field / AAD-bound zero in session-key mode
                7,  // Argon iterations
                8,  // Argon parallelism
                9,  // first salt byte
                24, // last salt byte
                25, // first IV byte
                36, // last IV byte
            )
            v.payload.startsWith(SecurePayloadCodec.PREFIX_V3) -> listOf(
                0,  // version
                1,  // type
                2,  // key mode
                3,  // first salt byte
                18, // last salt byte
                19, // first IV byte
                30, // last IV byte
            )
            else -> error("${v.name}: unsupported vector prefix")
        }.filter { it < v.headerLen }

    private fun decrypt(file: VectorFile, v: VectorFile.Vector): SecurePayloadCodec.DecryptedPayload =
        when (v.keyMode) {
            "passphrase" -> SecurePayloadCodec.decryptEnvelope(v.payload, file.passphrase)
            "session" -> SecurePayloadCodec.decryptEnvelopeWithSessionKey(v.payload, file.sessionKey)
            else -> throw IllegalStateException("${v.name}: unknown key mode ${v.keyMode}")
        }

    private fun loadVectors(): VectorFile {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("vectors.txt").use { it.readBytes() }.toString(StandardCharsets.UTF_8)
        return VectorFile.parse(text)
    }

    /** Mirror of :shared-protocol's parser — kept self-contained since the modules don't share code. */
    class VectorFile private constructor(
        val passphrase: String,
        val sessionKey: ByteArray,
        val vectors: List<Vector>,
    ) {
        data class Vector(
            val name: String,
            val keyMode: String,
            val type: Int,
            val headerLen: Int,
            val page: Int,
            val total: Int,
            val totalBytes: Int,
            val body: ByteArray,
            val payload: String,
        )

        companion object {
            fun parse(text: String): VectorFile {
                var passphrase: String? = null
                var sessionKey: ByteArray? = null
                val vectors = ArrayList<Vector>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val f = line.split("|")
                    when (f[0]) {
                        "meta" -> {
                            passphrase = String(b64(f[1]), StandardCharsets.UTF_8)
                            sessionKey = hex(f[2])
                        }
                        "vec" -> vectors.add(
                            Vector(
                                name = f[1],
                                keyMode = f[3],
                                type = f[4].toInt(),
                                headerLen = f[5].toInt(),
                                page = f[6].toInt(),
                                total = f[7].toInt(),
                                totalBytes = f[8].toInt(),
                                body = b64(f[9]),
                                payload = f[10],
                            ),
                        )
                        else -> throw IllegalStateException("unknown row type: ${f[0]}")
                    }
                }
                return VectorFile(
                    requireNotNull(passphrase) { "vectors.txt missing meta row" },
                    requireNotNull(sessionKey) { "vectors.txt missing meta row" },
                    vectors,
                )
            }

            private fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

            private fun hex(s: String): ByteArray =
                ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
        }
    }
}
