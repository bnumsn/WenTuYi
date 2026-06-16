package com.wentuyi.protocol

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Decodes the repo-level canonical vectors (`protocol-fixtures/vectors.txt`) with the JVM
 * codec and asserts every one round-trips, plus that flipping any single header byte makes
 * decryption fail (the GCM-AAD binding). `:app` runs the same file against its own codec copy
 * in androidTest, so if the two implementations ever drift one suite goes red.
 */
class VectorContractTest {

    @Test
    fun everyVectorDecryptsToItsExpectedPlaintext() {
        val file = ProtocolVectorFile.load()
        assertTrue(file.vectors.isNotEmpty(), "no vectors loaded")
        for (v in file.vectors) {
            val decrypted = decrypt(file, v)
            assertEquals(v.type, decrypted.type, "${v.name}: type")
            assertContentEquals(v.body, decrypted.data, "${v.name}: body")
            if (v.type == SecurePayloadCodec.TYPE_IMAGE_PAGE) {
                assertEquals(v.page, decrypted.pageNumber, "${v.name}: pageNumber")
                assertEquals(v.total, decrypted.pageTotal, "${v.name}: pageTotal")
            }
            if (v.type == SecurePayloadCodec.TYPE_IMAGE_CHUNK) {
                assertEquals(v.page, decrypted.pageNumber, "${v.name}: chunkNumber")
                assertEquals(v.total, decrypted.pageTotal, "${v.name}: chunkTotal")
                assertEquals(v.totalBytes, decrypted.totalBytes, "${v.name}: totalBytes")
            }
        }
    }

    @Test
    fun flippingAnyHeaderByteFailsDecryption() {
        val file = ProtocolVectorFile.load()
        for (v in file.vectors) {
            val prefix = v.payload.substring(0, 5)
            val packed = Encoding.b64Decode(v.payload.substring(prefix.length))
            for (i in 0 until v.headerLen) {
                val tampered = packed.copyOf()
                tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
                val mutated = prefix + Encoding.b64(tampered)
                assertFails("${v.name}: header byte $i tamper was not rejected") {
                    decrypt(file, v.copy(payload = mutated))
                }
            }
        }
    }

    private fun decrypt(file: ProtocolVectorFile, v: ProtocolVectorFile.Vector): SecurePayloadCodec.DecryptedPayload =
        when (v.keyMode) {
            "passphrase" -> SecurePayloadCodec.decryptEnvelope(v.payload, file.passphrase)
            "session" -> SecurePayloadCodec.decryptEnvelopeWithSessionKey(v.payload, file.sessionKey)
            else -> fail("${v.name}: unknown key mode ${v.keyMode}")
        }
}

/**
 * Minimal parser for the dependency-free `vectors.txt` format (see protocol-fixtures/README.md).
 * Deliberately tiny and self-contained so the same logic can be mirrored in :app's androidTest
 * without sharing a module.
 */
class ProtocolVectorFile private constructor(
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
        fun load(): ProtocolVectorFile {
            val text = ProtocolVectorFile::class.java.getResourceAsStream("/vectors.txt")
                ?.readBytes()?.toString(StandardCharsets.UTF_8)
                ?: error("vectors.txt not found on the test classpath")
            return parse(text)
        }

        fun parse(text: String): ProtocolVectorFile {
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
                    else -> error("unknown row type: ${f[0]}")
                }
            }
            return ProtocolVectorFile(
                requireNotNull(passphrase) { "vectors.txt missing meta row" },
                requireNotNull(sessionKey) { "vectors.txt missing meta row" },
                vectors,
            )
        }

        private fun b64(s: String): ByteArray = Encoding.b64Decode(s)

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    }
}
