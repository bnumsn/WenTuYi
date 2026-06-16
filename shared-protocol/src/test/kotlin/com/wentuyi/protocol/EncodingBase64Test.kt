package com.wentuyi.protocol

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Locks the pure-Kotlin [Encoding] Base64 to be byte-identical to `java.util.Base64`
 * (standard + URL-safe, encode + decode). That JDK encoder is in turn byte-identical to
 * `android.util.Base64` with NO_WRAP / NO_WRAP|URL_SAFE, so this is the JVM half of proving
 * the app can migrate onto :shared-protocol's codec without breaking stored data. The device
 * half (Encoding == android.util.Base64) lives in :app androidTest EncodingParityTest.
 */
class EncodingBase64Test {

    @Test
    fun standardMatchesJdkAcrossAllLengths() {
        val enc = Base64.getEncoder()
        val dec = Base64.getDecoder()
        // length 0..260 covers every (size % 3) residue and multi-block inputs
        for (len in 0..260) {
            val bytes = Random(len.toLong()).nextBytes(len)
            assertEquals(enc.encodeToString(bytes), Encoding.b64(bytes), "encode len=$len")
            assertContentEquals(bytes, Encoding.b64Decode(Encoding.b64(bytes)), "round-trip len=$len")
            // our decoder must also accept what the JDK encoder produced
            assertContentEquals(dec.decode(enc.encodeToString(bytes)), Encoding.b64Decode(enc.encodeToString(bytes)), "decode-jdk len=$len")
        }
    }

    @Test
    fun urlSafeMatchesJdkAcrossAllLengths() {
        val enc = Base64.getUrlEncoder()
        for (len in 0..260) {
            val bytes = Random(1000L + len).nextBytes(len)
            assertEquals(enc.encodeToString(bytes), Encoding.b64Url(bytes), "url encode len=$len")
            assertContentEquals(bytes, Encoding.b64UrlDecode(Encoding.b64Url(bytes)), "url round-trip len=$len")
        }
    }

    @Test
    fun knownVectorsAndEdges() {
        assertEquals("", Encoding.b64(ByteArray(0)))
        assertContentEquals(ByteArray(0), Encoding.b64Decode(""))
        assertEquals("Zg==", Encoding.b64("f".toByteArray()))
        assertEquals("Zm8=", Encoding.b64("fo".toByteArray()))
        assertEquals("Zm9v", Encoding.b64("foo".toByteArray()))
        assertEquals("Zm9vYg==", Encoding.b64("foob".toByteArray()))
        // bytes that exercise the +// vs -/_ alphabet difference
        val highBytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        assertEquals("+/+/", Encoding.b64(highBytes))
        assertEquals("-_-_", Encoding.b64Url(highBytes))
    }

    @Test
    fun malformedInputThrowsIllegalArgument() {
        // MessageDecryptor depends on this exact exception type for malformed envelopes.
        assertFails { Encoding.b64Decode("!!!!") }
        assertFails { Encoding.b64Decode("Zm9v====x") }
    }

    @Test
    fun rejectsNonCanonicalRepresentations() {
        // length % 4 == 1 is never valid (a lone char carries only 6 bits, not a whole byte),
        // so you can't append one char to a 4k-length payload and decode to the same bytes.
        assertFails { Encoding.b64Decode("A") }
        assertFails { Encoding.b64Decode("Zm9vx") }              // 5 chars, %4==1
        // Non-zero residual bits past the last whole byte are rejected:
        assertEquals("fo", String(Encoding.b64Decode("Zm8")))   // residual 2 bits zero → ok
        assertFails { Encoding.b64Decode("Zm9") }               // residual 2 bits non-zero → reject
        // Canonical forms (with or without padding) still decode:
        assertContentEquals("foo".toByteArray(), Encoding.b64Decode("Zm9v"))
        assertContentEquals("foob".toByteArray(), Encoding.b64Decode("Zm9vYg"))   // 6 chars, valid
        assertContentEquals("foob".toByteArray(), Encoding.b64Decode("Zm9vYg=="))
    }
}
