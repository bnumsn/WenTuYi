package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.RatchetStateCodec
import java.nio.file.Files
import java.nio.file.Path

/** Local state is bound to both long-term identities, independently of the peer's name. */
internal class StoredRatchet(
    val localPublicKey: ByteArray,
    val peerPublicKey: ByteArray,
    val state: DoubleRatchet.State,
) {
    fun requireIdentities(local: ByteArray, peer: ByteArray) {
        require(localPublicKey.contentEquals(local) && peerPublicKey.contentEquals(peer)) {
            "ratchet identity changed; run peer-reset or ratchet-init before using this session"
        }
    }

    fun write(path: Path) = SecretFiles.write(path,
        "WTYRS1\n${Encoding.b64Url(localPublicKey)}\n${Encoding.b64Url(peerPublicKey)}\n" +
            RatchetStateCodec.encodeText(state))

    companion object {
        /** Reset can replace legacy/unbound state, but must retire its epoch as well. */
        fun epochForReset(path: Path): Long {
            if (!Files.exists(path)) return 0L
            val text = Files.readString(path)
            val encoded = if (text.startsWith("WTYRS1\n")) text.split('\n', limit = 4).getOrNull(3)
                else text
            return encoded?.let { runCatching { RatchetStateCodec.decodeText(it).epoch }.getOrNull() }
                ?: 0L // A corrupt file contains no usable session to retire.
        }

        fun read(path: Path): StoredRatchet {
            val parts = Files.readString(path).split('\n', limit = 4)
            require(parts.size == 4 && parts[0] == "WTYRS1") {
                "unbound or invalid ratchet state at $path; run peer-reset or ratchet-init to replace it"
            }
            val local = Encoding.b64UrlDecode(parts[1])
            val peer = Encoding.b64UrlDecode(parts[2])
            require(local.size == 32 && peer.size == 32) { "invalid ratchet identity binding" }
            return StoredRatchet(local, peer, RatchetStateCodec.decodeText(parts[3]))
        }
    }
}
