package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.RatchetStateCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * On-disk state for the desktop: one identity plus a set of peers, each with its own
 * ratchet session.
 *
 * Why this exists: the desktop CLI's crypto commands were all stateless, so every caller
 * had to carry the identity backup, the peer's public key and the ratchet state file around
 * itself. The Linux/Windows bridges never did — they only ever called `encrypt-text`, i.e.
 * the shared-passphrase path — which meant a desktop user could not read a WTY5 message
 * from an Android contact at all, and the more carefully the two of them verified each
 * other the more broken it got. With a profile, [WentuyiCli]'s `send` / `receive` can pick
 * the best available protocol on their own and the bridges just pass text through.
 *
 * Layout under `WENTUYI_HOME` (default `~/.config/wentuyi`), all files 0600:
 * ```
 *   identity            WTYB1 backup — this IS the private key
 *   passphrase          optional shared key for the legacy path
 *   peers/<name>.pub    peer's X25519 public key, base64url
 *   peers/<name>.ratchet   serialized ratchet session, if one has been opened
 * ```
 * There is no Keystore equivalent here, so these are plaintext secrets on disk; the file
 * mode is the only protection and `docs`/README say so plainly.
 */
class Profile(val home: Path) {

    companion object {
        fun default(): Profile {
            val env = System.getenv("WENTUYI_HOME")?.takeIf { it.isNotEmpty() }
            val base = if (env != null) Path.of(env)
            else Path.of(System.getProperty("user.home"), ".config", "wentuyi")
            return Profile(base)
        }

        /** A peer name has to be a safe single path segment — it becomes a filename. */
        fun requireName(name: String): String {
            require(name.isNotEmpty() && name.length <= 64) { "peer name must be 1-64 chars" }
            require(name.all { it.isLetterOrDigit() || it in "-_." }) {
                "peer name may only contain letters, digits, '-', '_' and '.'"
            }
            require(name != "." && name != "..") { "invalid peer name" }
            return name
        }
    }

    private val identityFile: Path get() = home.resolve("identity")
    private val passphraseFile: Path get() = home.resolve("passphrase")
    private val peersDir: Path get() = home.resolve("peers")

    // ─── Identity ─────────────────────────────────────────────────────────────

    fun hasIdentity(): Boolean = Files.exists(identityFile)

    fun loadIdentity(): KeyExchange.Identity {
        if (!hasIdentity()) {
            throw IllegalStateException("no identity in $home — run: desktop-cli init")
        }
        return KeyExchange.decodeBackup(Files.readString(identityFile).trim())
    }

    /** Creates the profile identity. Refuses to overwrite: that would orphan every peer. */
    fun createIdentity(): KeyExchange.Identity {
        check(!hasIdentity()) { "identity already exists in $home (delete it by hand to start over)" }
        val identity = KeyExchange.generateIdentity()
        writeSecret(identityFile, KeyExchange.encodeBackup(identity))
        return identity
    }

    fun importIdentity(backup: String): KeyExchange.Identity {
        val identity = KeyExchange.decodeBackup(backup.trim())
        writeSecret(identityFile, KeyExchange.encodeBackup(identity))
        return identity
    }

    // ─── Shared passphrase (legacy path) ──────────────────────────────────────

    fun passphrase(): String? =
        System.getenv("WENTUYI_PASSPHRASE")?.takeIf { it.isNotEmpty() }
            ?: if (Files.exists(passphraseFile)) {
                Files.readString(passphraseFile).trim().takeIf { it.isNotEmpty() }
            } else null

    fun setPassphrase(value: String) {
        require(value.isNotBlank()) { "passphrase is blank" }
        writeSecret(passphraseFile, value)
    }

    // ─── Peers ────────────────────────────────────────────────────────────────

    fun peerNames(): List<String> {
        if (!Files.isDirectory(peersDir)) return emptyList()
        Files.list(peersDir).use { stream ->
            return stream.map { it.fileName.toString() }
                .filter { it.endsWith(".pub") }
                .map { it.removeSuffix(".pub") }
                .sorted()
                .toList()
        }
    }

    fun addPeer(name: String, publicKey: ByteArray) {
        requireName(name)
        require(publicKey.size == 32) { "peer public key must be 32 bytes" }
        // Probe the key through a throwaway ECDH so a low-order / unusable key is refused
        // at "add peer" time with a clear message, instead of blowing up at first send.
        // KeyExchange.ecdh is where that check lives; there is no standalone validator.
        val probe = KeyExchange.generateIdentity()
        try {
            com.wentuyi.protocol.CryptoUtils.wipe(KeyExchange.ecdh(probe.privateKey, publicKey))
        } catch (e: Exception) {
            throw IllegalArgumentException("peer public key is unusable (low-order or malformed)", e)
        } finally {
            com.wentuyi.protocol.CryptoUtils.wipe(probe.privateKey)
        }
        writeSecret(peersDir.resolve("$name.pub"), Encoding.b64Url(publicKey))
    }

    fun peerPublicKey(name: String): ByteArray {
        val file = peersDir.resolve("${requireName(name)}.pub")
        if (!Files.exists(file)) throw IllegalArgumentException("unknown peer: $name")
        return Encoding.b64UrlDecode(Files.readString(file).trim())
    }

    fun removePeer(name: String) {
        Files.deleteIfExists(peersDir.resolve("${requireName(name)}.pub"))
        Files.deleteIfExists(ratchetFile(name))
    }

    // ─── Ratchet sessions ─────────────────────────────────────────────────────

    private fun ratchetFile(name: String): Path =
        peersDir.resolve("${requireName(name)}.ratchet")

    fun loadRatchet(name: String): DoubleRatchet.State? {
        val file = ratchetFile(name)
        if (!Files.exists(file)) return null
        return runCatching { RatchetStateCodec.decodeText(Files.readString(file)) }.getOrNull()
    }

    fun saveRatchet(name: String, state: DoubleRatchet.State) {
        writeSecret(ratchetFile(name), RatchetStateCodec.encodeText(state))
    }

    fun clearRatchet(name: String) {
        Files.deleteIfExists(ratchetFile(name))
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    /** Writes 0600 where the filesystem has POSIX permissions (no-op on Windows). */
    private fun writeSecret(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, content.toByteArray(Charsets.UTF_8))
        runCatching {
            Files.setPosixFilePermissions(
                path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
