package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import java.nio.file.Files
import java.nio.file.Path

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
 *   peers/<name>.auth   versioned verification bound to both identities
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

    /** Hold across every read, crypto operation, and durable write in a command. */
    fun <T> transaction(block: () -> T): T = SecretFiles.withLock(home.resolve(".lock"), block)

    // ─── Identity ─────────────────────────────────────────────────────────────

    fun hasIdentity(): Boolean = transaction { Files.exists(identityFile) }

    fun loadIdentity(): KeyExchange.Identity = transaction {
        if (!hasIdentity()) {
            throw IllegalStateException("no identity in $home — run: desktop-cli init")
        }
        KeyExchange.decodeBackup(Files.readString(identityFile).trim())
    }

    /** Creates the profile identity. Refuses to overwrite: that would orphan every peer. */
    fun createIdentity(): KeyExchange.Identity = transaction {
        check(!hasIdentity()) { "identity already exists in $home (delete it by hand to start over)" }
        val identity = KeyExchange.generateIdentity()
        clearAllPeerState()
        SecretFiles.write(identityFile, KeyExchange.encodeBackup(identity))
        identity
    }

    fun importIdentity(backup: String): KeyExchange.Identity = transaction {
        val identity = KeyExchange.decodeBackup(backup.trim())
        val previousPublicKey = runCatching { loadIdentity().publicKey }.getOrNull()
        val unchanged = previousPublicKey?.contentEquals(identity.publicKey) == true
        // Delete before replacing the identity. Interruption may lose a session, but can
        // never leave an old session usable under the new identity.
        if (!unchanged) clearAllPeerState()
        SecretFiles.write(identityFile, KeyExchange.encodeBackup(identity))
        identity
    }

    // ─── Shared passphrase (legacy path) ──────────────────────────────────────

    fun passphrase(): String? = transaction {
        System.getenv("WENTUYI_PASSPHRASE")?.takeIf { it.isNotEmpty() }
            ?: if (Files.exists(passphraseFile)) {
                Files.readString(passphraseFile).trim().takeIf { it.isNotEmpty() }
            } else null
    }

    fun setPassphrase(value: String) = transaction {
        require(value.isNotBlank()) { "passphrase is blank" }
        SecretFiles.write(passphraseFile, value)
    }

    // ─── Peers ────────────────────────────────────────────────────────────────

    fun peerNames(): List<String> = transaction {
        if (!Files.isDirectory(peersDir)) return@transaction emptyList()
        Files.list(peersDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".pub") }
                .map { it.removeSuffix(".pub") }
                .sorted()
                .toList()
        }
    }

    fun addPeer(name: String, publicKey: ByteArray) = transaction {
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
        val file = peersDir.resolve("$name.pub")
        val unchanged = Files.exists(file) && peerPublicKey(name).contentEquals(publicKey)
        if (!unchanged) {
            clearVerification(name)
            clearRatchet(name)
        }
        SecretFiles.write(file, Encoding.b64Url(publicKey))
    }

    fun peerPublicKey(name: String): ByteArray = transaction {
        val file = peersDir.resolve("${requireName(name)}.pub")
        if (!Files.exists(file)) throw IllegalArgumentException("unknown peer: $name")
        Encoding.b64UrlDecode(Files.readString(file).trim())
    }

    fun removePeer(name: String) = transaction {
        clearVerification(name)
        clearRatchet(name)
        SecretFiles.delete(peersDir.resolve("${requireName(name)}.pub"))
    }

    // Verification never carries over from legacy short codes or a changed identity.
    private fun authFile(name: String): Path = peersDir.resolve("${requireName(name)}.auth")

    private fun authenticationRecord(name: String): String =
        "WTYA${KeyExchange.AUTH_VERSION}\n${Encoding.b64Url(loadIdentity().publicKey)}\n" +
            Encoding.b64Url(peerPublicKey(name))

    fun isPeerVerified(name: String): Boolean = transaction {
        val file = authFile(name)
        Files.exists(file) && runCatching {
            Files.readString(file) == authenticationRecord(name)
        }.getOrDefault(false)
    }

    fun requirePeerVerified(name: String) {
        check(isPeerVerified(name)) {
            "peer '$name' is unverified; compare the complete 256-bit code over a trusted " +
                "channel, then run: desktop-cli peer-verify --peer $name --code FULLCODE"
        }
    }

    fun verifyPeer(name: String, code: String) = transaction {
        val normalized = code.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
        val expected = KeyExchange.shortAuthString(loadIdentity(), peerPublicKey(name))
            .filterNot(Char::isWhitespace)
        require(normalized.length == 64 && normalized == expected) {
            "authentication code does not match; compare all 64 hexadecimal characters"
        }
        SecretFiles.write(authFile(name), authenticationRecord(name))
    }

    private fun clearVerification(name: String) = SecretFiles.delete(authFile(name))

    fun epochForReset(name: String): Long = transaction {
        StoredRatchet.epochForReset(ratchetFile(name))
    }

    // ─── Ratchet sessions ─────────────────────────────────────────────────────

    private fun ratchetFile(name: String): Path =
        peersDir.resolve("${requireName(name)}.ratchet")

    fun loadRatchet(name: String): DoubleRatchet.State? = transaction {
        val file = ratchetFile(name)
        if (!Files.exists(file)) return@transaction null
        val stored = StoredRatchet.read(file)
        stored.requireIdentities(loadIdentity().publicKey, peerPublicKey(name))
        stored.state
    }

    fun saveRatchet(name: String, state: DoubleRatchet.State) = transaction {
        StoredRatchet(loadIdentity().publicKey, peerPublicKey(name), state).write(ratchetFile(name))
    }

    fun clearRatchet(name: String) = transaction {
        SecretFiles.delete(ratchetFile(name))
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun clearAllPeerState() {
        if (!Files.isDirectory(peersDir)) return
        Files.list(peersDir).use { files ->
            files.filter { it.fileName.toString().endsWith(".ratchet") ||
                it.fileName.toString().endsWith(".auth") }
                .forEach(SecretFiles::delete)
        }
    }
}
