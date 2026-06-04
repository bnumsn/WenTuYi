package com.wentuyi.protocol

object PayloadChunks {
    const val MULTI_PREFIX = "WTYP1"
    private const val DELIM = "|"
    private const val MAX_QR_BYTES = 800
    private const val MAX_QR_PAGES = 32

    fun chunkPayload(payload: String): List<String> {
        if (payload.length <= MAX_QR_BYTES) return listOf(payload)
        val total = (payload.length + MAX_QR_BYTES - 1) / MAX_QR_BYTES
        require(total <= MAX_QR_PAGES) { "payload needs $total QR pages, max is $MAX_QR_PAGES" }
        val id = chunkIdFor(payload)
        return (0 until total).map { i ->
            val start = i * MAX_QR_BYTES
            val end = minOf(start + MAX_QR_BYTES, payload.length)
            "$MULTI_PREFIX$DELIM$id$DELIM${i + 1}$DELIM$total$DELIM" + payload.substring(start, end)
        }
    }

    fun assemblePayloadFromTexts(texts: List<String>): String {
        require(texts.isNotEmpty()) { "no QR text provided" }
        for (t in texts) if (SecurePayloadCodec.isPayload(t)) return t
        if (texts.all { it.startsWith("$MULTI_PREFIX$DELIM") }) return assembleMultiQrChunks(texts)
        throw IllegalArgumentException("not a Wentuyi encrypted QR payload")
    }

    private fun assembleMultiQrChunks(texts: List<String>): String {
        var id: String? = null
        var total = -1
        val parts = HashMap<Int, String>()
        for (t in texts) {
            val fields = t.split(DELIM, limit = 5)
            require(fields.size == 5 && fields[0] == MULTI_PREFIX) { "invalid WTYP1 chunk" }
            val chunkId = fields[1]
            val n = fields[2].toIntOrNull() ?: throw IllegalArgumentException("invalid chunk page")
            val t2 = fields[3].toIntOrNull() ?: throw IllegalArgumentException("invalid chunk total")
            require(t2 in 1..MAX_QR_PAGES && n in 1..t2) { "chunk page/total out of range" }
            if (id == null) {
                id = chunkId
                total = t2
            } else {
                require(id == chunkId) { "chunks belong to different payloads" }
                require(total == t2) { "chunk total mismatch" }
            }
            parts[n] = fields[4]
        }
        if (parts.size != total) {
            val missing = (1..total).filter { it !in parts.keys }
            throw IllegalArgumentException("QR chunks incomplete: ${parts.size}/$total, missing ${missing.joinToString(",")}")
        }
        val joined = buildString { for (i in 1..total) append(parts[i]) }
        require(chunkIdFor(joined) == id) { "chunk hash mismatch" }
        require(SecurePayloadCodec.isPayload(joined)) { "assembled payload is not WTY" }
        return joined
    }

    private fun chunkIdFor(payload: String): String {
        val digest = CryptoUtils.sha256(payload.toByteArray(Charsets.UTF_8))
        return Encoding.Base32.encode(digest.copyOf(5)).take(8).lowercase()
    }
}
