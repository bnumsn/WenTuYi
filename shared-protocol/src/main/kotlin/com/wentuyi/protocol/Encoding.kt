package com.wentuyi.protocol

import java.util.Base64

object Encoding {
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64Decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun b64Url(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)
    fun b64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

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
