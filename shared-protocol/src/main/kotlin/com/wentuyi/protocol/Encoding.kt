package com.wentuyi.protocol

object Encoding {
    // Pure-Kotlin Base64 so :shared-protocol stays portable to every platform with no JDK
    // floor: java.util.Base64 only exists on Android API 26+, but the app ships minSdk 23.
    // Output is byte-identical to java.util.Base64.getEncoder()/getUrlEncoder() (RFC 4648,
    // padded, single line), which in turn equals android.util.Base64 with NO_WRAP /
    // NO_WRAP|URL_SAFE — so swapping the app onto this codec preserves every stored contact,
    // identity QR and on-wire payload. Decoders throw IllegalArgumentException on malformed
    // input, matching both platform libraries (MessageDecryptor relies on that).
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun b64(bytes: ByteArray): String = encode(bytes, STD)
    fun b64Decode(text: String): ByteArray = decode(text, STD)
    fun b64Url(bytes: ByteArray): String = encode(bytes, URL)
    fun b64UrlDecode(text: String): ByteArray = decode(text, URL)

    private fun encode(bytes: ByteArray, alphabet: String): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(alphabet[n ushr 18 and 0x3F])
            sb.append(alphabet[n ushr 12 and 0x3F])
            sb.append(alphabet[n ushr 6 and 0x3F])
            sb.append(alphabet[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                sb.append(alphabet[n ushr 18 and 0x3F])
                sb.append(alphabet[n ushr 12 and 0x3F])
                sb.append('=').append('=')
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(alphabet[n ushr 18 and 0x3F])
                sb.append(alphabet[n ushr 12 and 0x3F])
                sb.append(alphabet[n ushr 6 and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    private fun decode(text: String, alphabet: String): ByteArray {
        // Strip padding; like both platform libraries we treat '=' as a terminator, not data.
        var end = text.length
        while (end > 0 && text[end - 1] == '=') end--
        if (end == 0) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(end * 3 / 4)
        var buffer = 0
        var bits = 0
        for (idx in 0 until end) {
            val v = alphabet.indexOf(text[idx])
            require(v >= 0) { "invalid base64 character at $idx" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer ushr bits and 0xFF)
            }
        }
        return out.toByteArray()
    }

    object Base32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun encode(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val sb = StringBuilder()
            var buffer = 0
            var bitsLeft = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bitsLeft += 8
                while (bitsLeft >= 5) {
                    val index = (buffer shr (bitsLeft - 5)) and 0x1F
                    sb.append(ALPHABET[index])
                    bitsLeft -= 5
                }
            }
            if (bitsLeft > 0) {
                val index = (buffer shl (5 - bitsLeft)) and 0x1F
                sb.append(ALPHABET[index])
            }
            return sb.toString()
        }

        fun decode(text: String): ByteArray {
            val clean = text.uppercase().filter { it in ALPHABET }
            if (clean.isEmpty()) return ByteArray(0)
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bitsLeft = 0
            for (c in clean) {
                val value = ALPHABET.indexOf(c)
                buffer = (buffer shl 5) or value
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    val b = (buffer shr (bitsLeft - 8)) and 0xFF
                    out.write(b)
                    bitsLeft -= 8
                }
            }
            return out.toByteArray()
        }
    }
}
