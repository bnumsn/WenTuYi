package com.wentuyi.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Serializes a [DoubleRatchet.State] so a stateless client (the desktop CLI) can keep a
 * ratchet session across process boundaries.
 *
 * This is a *local storage* format, not a wire format: ratchet state is never transmitted,
 * so it does not need to agree with any other platform — only the WTY5 envelope does, and
 * that lives in [DoubleRatchet]. The Android app keeps its own JSON encoding for the same
 * reason (it predates this one and is already on users' devices).
 *
 * The encoded blob contains **private key material** (the current ratchet private key, the
 * root key, both chain keys and every cached skipped message key). Callers must store it
 * with the same care as an identity backup — the CLI writes it 0600 and says so.
 */
object RatchetStateCodec {
    private const val VERSION = 1

    fun encode(state: DoubleRatchet.State): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(VERSION)
            d.writeLong(state.epoch)
            d.writeBlob(state.rk)
            d.writeBlob(state.dhsPub)
            d.writeBlob(state.dhsPriv)
            d.writeOptionalBlob(state.dhr)
            d.writeOptionalBlob(state.cks)
            d.writeOptionalBlob(state.ckr)
            d.writeInt(state.ns); d.writeInt(state.nr); d.writeInt(state.pn)
            d.writeInt(state.skipped.size)
            for ((k, v) in state.skipped) {
                val key = k.toByteArray(StandardCharsets.UTF_8)
                d.writeBlob(key)
                d.writeBlob(v)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): DoubleRatchet.State {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val version = d.readUnsignedByte()
            require(version == VERSION) { "unsupported ratchet state version: $version" }
            val epoch = d.readLong()
            val rk = d.readBlob()
            val dhsPub = d.readBlob()
            val dhsPriv = d.readBlob()
            val dhr = d.readOptionalBlob()
            val cks = d.readOptionalBlob()
            val ckr = d.readOptionalBlob()
            val ns = d.readInt(); val nr = d.readInt(); val pn = d.readInt()
            val count = d.readInt()
            require(count in 0..100_000) { "skipped-key count out of range: $count" }
            val skipped = LinkedHashMap<String, ByteArray>(maxOf(count, 1))
            repeat(count) {
                val key = String(d.readBlob(), StandardCharsets.UTF_8)
                skipped[key] = d.readBlob()
            }
            return DoubleRatchet.State(
                epoch = epoch, rk = rk, dhsPub = dhsPub, dhsPriv = dhsPriv,
                dhr = dhr, cks = cks, ckr = ckr, ns = ns, nr = nr, pn = pn, skipped = skipped,
            )
        }
    }

    /** Text form for storing in a file — same bytes, Base64 so the file stays copy-pasteable. */
    fun encodeText(state: DoubleRatchet.State): String = Encoding.b64(encode(state))

    fun decodeText(text: String): DoubleRatchet.State = decode(Encoding.b64Decode(text.trim()))

    private fun DataOutputStream.writeBlob(b: ByteArray) {
        writeInt(b.size); write(b)
    }

    private fun DataOutputStream.writeOptionalBlob(b: ByteArray?) {
        if (b == null) writeInt(-1) else writeBlob(b)
    }

    private fun DataInputStream.readBlob(): ByteArray {
        val len = readInt()
        require(len in 0..1_000_000) { "blob length out of range: $len" }
        val b = ByteArray(len)
        readFully(b)
        return b
    }

    private fun DataInputStream.readOptionalBlob(): ByteArray? {
        val len = readInt()
        if (len == -1) return null
        require(len in 0..1_000_000) { "blob length out of range: $len" }
        val b = ByteArray(len)
        readFully(b)
        return b
    }
}
