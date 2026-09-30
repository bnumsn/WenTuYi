package com.wentuyi.protocol

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

/** Shared wire-size budget, including the five-character WTY prefix and Base64 padding. */
object PayloadLimits {
    const val MAX_PAYLOAD_CHARS = 512 * 1024
    internal const val MAX_PACKED_BYTES = (MAX_PAYLOAD_CHARS - 5) / 4 * 3
    internal const val GCM_TAG_BYTES = 16

    fun requirePayloadSize(payload: String) {
        if (payload.length > MAX_PAYLOAD_CHARS) throw GeneralSecurityException("payload too large")
    }

    /** Count UTF-8 bytes before allocating the encoded buffer, including surrogate pairs. */
    fun utf8Bytes(text: String, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "negative plaintext limit" }
        require(text.length <= maxBytes) { "plaintext too large (maximum $maxBytes UTF-8 bytes)" }
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i < text.length && text[i].isLowSurrogate() -> {
                    i++
                    4
                }
                // String.toByteArray(UTF_8) substitutes '?' for an unpaired surrogate.
                c.isSurrogate() -> 1
                else -> 3
            }
            require(bytes <= maxBytes) { "plaintext too large (maximum $maxBytes UTF-8 bytes)" }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }
}
