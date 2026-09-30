package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.PayloadChunks
import com.wentuyi.protocol.PayloadLimits
import com.wentuyi.protocol.SecurePayloadCodec
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    // Force UTF-8 stdout/stderr on every platform. On Windows the default System.out uses the
    // console code page (e.g. GBK/IBM437), which mangles non-ASCII (Chinese) output into "?"/
    // replacement chars; on Linux this is already UTF-8 so it's a no-op. stdin is read as
    // explicit UTF-8 elsewhere, so this makes the CLI byte-deterministic across platforms.
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))
    try {
        run(args.toList())
    } catch (e: Exception) {
        System.err.println("error: ${e.message ?: e::class.java.simpleName}")
        kotlin.system.exitProcess(2)
    }
}

private fun run(args: List<String>) {
    if (args.isEmpty() || args[0] == "help" || args[0] == "--help") {
        printHelp()
        return
    }
    when (args[0]) {
        "encrypt-text" -> {
            val passphrase = resolvePassphrase(args)
            val text = resolveText(args.drop(1), setOf("--passphrase"))
            println(SecurePayloadCodec.encryptTextToPayload(text, passphrase))
        }
        "decrypt-text" -> {
            val passphrase = resolvePassphrase(args)
            val payload = resolvePayload(args.drop(1), setOf("--passphrase"))
            print(SecurePayloadCodec.decryptPayload(payload, passphrase))
        }
        "plain-image" -> {
            val out = Path.of(option(args, "--out"))
            val text = resolveText(args.drop(1), setOf("--out"))
            println(DesktopImageCodec.writePlainTextImage(text, out).toAbsolutePath())
        }
        "encrypted-qr" -> {
            val passphrase = resolvePassphrase(args)
            val outDir = Path.of(option(args, "--out-dir"))
            val prefix = optionOrNull(args, "--prefix") ?: "wentuyi-qr"
            val text = resolveText(args.drop(1), setOf("--passphrase", "--out-dir", "--prefix"))
            val payload = SecurePayloadCodec.encryptTextToPayload(text, passphrase)
            DesktopImageCodec.writePayloadQrImages(payload, outDir, prefix).forEach { println(it.toAbsolutePath()) }
        }
        "payload-qr" -> {
            val outDir = Path.of(option(args, "--out-dir"))
            val prefix = optionOrNull(args, "--prefix") ?: "wentuyi-qr"
            val payload = resolvePayload(args.drop(1), setOf("--out-dir", "--prefix"))
            DesktopImageCodec.writePayloadQrImages(payload, outDir, prefix).forEach { println(it.toAbsolutePath()) }
        }
        "gen-identity" -> {
            val name = optionOrNull(args, "--name") ?: "文图易用户"
            val identity = KeyExchange.generateIdentity()
            println("publicKey=${Encoding.b64Url(identity.publicKey)}")
            println("privateKey=${Encoding.b64Url(identity.privateKey)}")
            println("fingerprint=${identity.fingerprint}")
            println("backup=${KeyExchange.encodeBackup(identity)}")
            println("identityQr=${KeyExchange.encodeIdentityForQr(name, identity.publicKey)}")
        }
        "restore-backup" -> {
            // The WTYB1 backup IS the private key — keep it off argv: prefer WENTUYI_BACKUP
            // env, then --stdin; positional remains as an explicit fallback.
            val backup = System.getenv("WENTUYI_BACKUP")?.takeIf { it.isNotEmpty() }
                ?: if (args.contains("--stdin")) {
                    readInputText(System.`in`)
                } else {
                    args.drop(1).filterNot { it.startsWith("--") }.joinToString("")
                        .ifEmpty { throw IllegalArgumentException("missing backup (WENTUYI_BACKUP / --stdin / WTYB1...)") }
                }
            val identity = KeyExchange.decodeBackup(backup)
            println("publicKey=${Encoding.b64Url(identity.publicKey)}")
            println("privateKey=${Encoding.b64Url(identity.privateKey)}")
            println("fingerprint=${identity.fingerprint}")
        }
        "sas" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val peer = peerPublic(args)
            println(KeyExchange.shortAuthString(identity, peer))
        }
        "session-encrypt" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val secret = KeyExchange.deriveSharedSecret(identity, peerPublic(args))
            try {
                val text = resolveText(args.drop(1), setOf("--backup", "--peer-public", "--peer-qr"))
                println(SecurePayloadCodec.encryptTextWithSessionKey(text, secret))
            } finally {
                com.wentuyi.protocol.CryptoUtils.wipe(secret)
            }
        }
        "session-decrypt" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val secret = KeyExchange.deriveSharedSecret(identity, peerPublic(args))
            try {
                val payload = resolvePayload(args.drop(1), setOf("--backup", "--peer-public", "--peer-qr"))
                print(SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, secret).text())
            } finally {
                com.wentuyi.protocol.CryptoUtils.wipe(secret)
            }
        }
        // ─── Profile: one identity + peers, so the bridges don't route protocols ──
        "init" -> ProfileCommands.init(Profile.default())
        "whoami" -> ProfileCommands.whoami(Profile.default())
        "import-identity" -> {
            val backup = System.getenv("WENTUYI_BACKUP")?.takeIf { it.isNotEmpty() }
                ?: resolvePayload(args.drop(1), emptySet())
            val identity = Profile.default().importIdentity(backup)
            println("fingerprint=${identity.fingerprint}")
        }
        "set-passphrase" -> {
            val value = System.getenv("WENTUYI_PASSPHRASE")?.takeIf { it.isNotEmpty() }
                ?: resolvePayload(args.drop(1), emptySet())
            Profile.default().setPassphrase(value)
            println("passphrase saved")
        }
        "peer-add" -> {
            val profile = Profile.default()
            val name = option(args, "--name")
            profile.transaction {
                profile.addPeer(name, peerPublic(args))
                val identity = runCatching { profile.loadIdentity() }.getOrNull()
                if (identity != null) {
                    println("sas=${KeyExchange.shortAuthString(identity, profile.peerPublicKey(name))}")
                    System.err.println(
                        "Compare the complete 256-bit code with $name using a trusted channel, then run peer-verify.")
                }
            }
        }
        "peer-verify" -> {
            val name = option(args, "--peer")
            Profile.default().verifyPeer(name, option(args, "--code"))
            println("verified=$name authVersion=${KeyExchange.AUTH_VERSION}")
        }
        "peer-list" -> ProfileCommands.peerList(Profile.default())
        "peer-remove" -> Profile.default().removePeer(option(args, "--peer"))
        "peer-reset" -> {
            // Opens a fresh epoch we are the sender of; the peer adopts it on our next
            // message. The escape hatch for "their messages stopped decrypting".
            val profile = Profile.default()
            val name = option(args, "--peer")
            profile.transaction {
                val identity = profile.loadIdentity()
                profile.requirePeerVerified(name)
                val peer = profile.peerPublicKey(name)
                val epoch = DoubleRatchet.newEpoch(profile.epochForReset(name))
                profile.saveRatchet(name, DoubleRatchet.initSender(
                    DoubleRatchet.initialRootKey(identity, peer, epoch), peer, epoch))
                println("epoch=$epoch")
                System.err.println("Now send $name one message to complete the recovery.")
            }
        }
        "send" -> {
            val peer = optionOrNull(args, "--peer")
            val text = resolveText(args.drop(1), setOf("--peer"))
            ProfileCommands.send(Profile.default(), peer, text)
        }
        "receive" -> ProfileCommands.receive(
            Profile.default(), resolvePayload(args.drop(1), emptySet()))

        // ─── WTY5 Double Ratchet ──────────────────────────────────────────────────
        // The Android app sends WTY5 to every verified contact by default, so without these
        // a desktop peer simply cannot read messages from someone who verified them — the
        // more carefully the two users set the contact up, the more broken it was.
        // Unlike the stateless commands above, a ratchet needs somewhere to keep the session:
        // --state names that file, and every command rewrites it after a successful step.
        "ratchet-init" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val peer = peerPublic(args)
            val statePath = statePath(args)
            SecretFiles.withStateLock(statePath) {
                val epoch = DoubleRatchet.newEpoch(StoredRatchet.epochForReset(statePath))
                val state = DoubleRatchet.initSender(
                    DoubleRatchet.initialRootKey(identity, peer, epoch), peer, epoch)
                StoredRatchet(identity.publicKey, peer, state).write(statePath)
                println("epoch=$epoch")
                println("state=$statePath")
            }
        }
        "ratchet-encrypt" -> {
            val statePath = statePath(args)
            val text = resolveText(args.drop(1), setOf("--state"))
            SecretFiles.withStateLock(statePath) {
                val stored = readState(statePath)
                val payload = DoubleRatchet.encrypt(stored.state,
                    PayloadLimits.utf8Bytes(text, DoubleRatchet.MAX_PLAINTEXT_BYTES))
                stored.write(statePath)
                println(payload)
            }
        }
        "ratchet-decrypt" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val peer = peerPublic(args)
            val statePath = statePath(args)
            val payload = resolvePayload(
                args.drop(1), setOf("--backup", "--peer-public", "--peer-qr", "--state"))
            val headerEpoch = DoubleRatchet.peekEpoch(payload)
                ?: throw IllegalArgumentException("not a WTY5 ratchet payload")
            SecretFiles.withStateLock(statePath) {
                val stored = if (Files.exists(statePath)) {
                    readState(statePath).also { it.requireIdentities(identity.publicKey, peer) }.state
                } else null

                // Use the session we hold for this epoch; adopt a strictly newer one
                // (the peer reset); refuse a retired one to prevent replay.
                val state = when {
                    stored != null && stored.epoch == headerEpoch -> stored
                    stored != null && headerEpoch <= stored.epoch ->
                        throw IllegalStateException(
                            "ratchet session out of sync (payload epoch $headerEpoch <= local " +
                                "${stored.epoch}); run ratchet-init to start a fresh session")
                    else -> DoubleRatchet.initReceiver(
                        DoubleRatchet.initialRootKey(identity, peer, headerEpoch), identity, headerEpoch)
                }
                val plain = DoubleRatchet.decrypt(state, payload)
                StoredRatchet(identity.publicKey, peer, state).write(statePath)
                print(String(plain, Charsets.UTF_8))
            }
        }
        "ratchet-info" -> {
            val statePath = statePath(args)
            SecretFiles.withStateLock(statePath) {
                val state = readState(statePath).state
                println("epoch=${state.epoch}")
                println("sending=${state.cks != null}")
                println("receiving=${state.ckr != null}")
                println("ns=${state.ns} nr=${state.nr} pn=${state.pn} skipped=${state.skipped.size}")
            }
        }

        "chunk" -> PayloadChunks.chunkPayload(args.drop(1).joinToString(" ")).forEach(::println)
        "assemble" -> println(PayloadChunks.assemblePayloadFromTexts(args.drop(1)))
        else -> throw IllegalArgumentException("unknown command: ${args[0]}")
    }
}

// Secrets (shared passphrase, WTYB1 backup) are read from the environment by default so
// they never appear in argv — process command lines are world-readable on Linux via
// /proc/<pid>/cmdline and `ps -eww`, whereas /proc/<pid>/environ is restricted to the same
// uid/root. The --passphrase / --backup flags remain as an explicit (less safe) fallback.
private fun resolvePassphrase(args: List<String>): String =
    System.getenv("WENTUYI_PASSPHRASE")?.takeIf { it.isNotEmpty() }
        ?: option(args, "--passphrase")

private fun resolveBackup(args: List<String>): String =
    System.getenv("WENTUYI_BACKUP")?.takeIf { it.isNotEmpty() }
        ?: option(args, "--backup")

/** Plaintext from stdin is exact: spaces, tabs and trailing newlines are message data. */
private fun resolveText(args: List<String>, optionNames: Set<String>): String =
    if (args.contains("--stdin")) readPlaintext(System.`in`)
    else restAfterOptions(args, optionNames)

/** Wire payloads and backup codes permit whitespace added by terminals/pipelines. */
private fun resolvePayload(args: List<String>, optionNames: Set<String>): String =
    if (args.contains("--stdin")) readInputText(System.`in`)
    else restAfterOptions(args, optionNames)

/** One maximum-size wire payload plus CRLF; stop before an unbounded allocation. */
internal fun readPlaintext(input: InputStream): String {
    val maximum = PayloadLimits.MAX_PAYLOAD_CHARS + 2
    val bytes = input.readNBytes(maximum + 1)
    require(bytes.size <= maximum) { "stdin too large (maximum $maximum UTF-8 bytes)" }
    return bytes.toString(Charsets.UTF_8)
        .ifEmpty { throw IllegalArgumentException("empty stdin") }
}

internal fun readInputText(input: InputStream): String = readPlaintext(input).trim()
    .ifEmpty { throw IllegalArgumentException("empty stdin") }

private fun option(args: List<String>, name: String): String =
    optionOrNull(args, name) ?: throw IllegalArgumentException("missing $name")

private fun optionOrNull(args: List<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    require(index + 1 < args.size) { "missing value for $name" }
    return args[index + 1]
}

/**
 * Ratchet state holds live private key material (ratchet private key, root key, chain keys,
 * every cached skipped message key). The desktop has no Keystore, so the least we can do is
 * keep the file off other users' eyes — 0600 where the filesystem supports POSIX perms.
 */
private fun statePath(args: List<String>): Path {
    val path = Path.of(option(args, "--state")).toAbsolutePath().normalize()
    SecretFiles.createDirectories(path.parent)
    // A symlink and its target must use the same stable lock and replacement target.
    return if (Files.exists(path)) path.toRealPath() else path.parent.toRealPath().resolve(path.fileName)
}

private fun readState(path: Path): StoredRatchet {
    if (!Files.exists(path)) throw IllegalArgumentException("no ratchet state at $path (run ratchet-init)")
    return StoredRatchet.read(path)
}

private fun peerPublic(args: List<String>): ByteArray {
    optionOrNull(args, "--peer-public")?.let { return Encoding.b64UrlDecode(it) }
    optionOrNull(args, "--peer-qr")?.let { return KeyExchange.decodeIdentityFromQr(it).second }
    throw IllegalArgumentException("missing --peer-public or --peer-qr")
}

private fun restAfterOptions(args: List<String>, optionNames: Set<String>): String {
    val out = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val item = args[i]
        if (item in optionNames) {
            i += 2
        } else if (item.startsWith("--")) {
            throw IllegalArgumentException("unknown option $item")
        } else {
            out += item
            i++
        }
    }
    return out.joinToString(" ").ifEmpty { throw IllegalArgumentException("missing text/payload") }
}

private fun printHelp() {
    println(
        """
        Wentuyi desktop protocol CLI

        Secrets via env (preferred, keeps them out of argv / ps / /proc/cmdline):
          WENTUYI_PASSPHRASE  shared key   (else --passphrase KEY)
          WENTUYI_BACKUP      WTYB1 backup (else --backup WTYB1)
        Text/payload via stdin: append --stdin and pipe the message in (else positional).
        Plaintext whitespace is preserved; decrypted stdout is exact, with no added newline.
        Stdin accepts at most 512 KiB of UTF-8 plus a trailing CRLF. Encryption checks its
        smaller protocol byte limit before allocating the plaintext buffer.

        Commands:
          encrypt-text --passphrase KEY TEXT
          decrypt-text --passphrase KEY WTY_PAYLOAD
          plain-image --out FILE.png TEXT
          encrypted-qr --passphrase KEY --out-dir DIR [--prefix NAME] TEXT
          payload-qr --out-dir DIR [--prefix NAME] WTY_PAYLOAD
          gen-identity [--name NAME]
          restore-backup [WTYB1_BACKUP | --stdin]   (or WENTUYI_BACKUP env; backup = private key)
          sas --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT)
          session-encrypt --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT) TEXT
          session-decrypt --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT) WTY_PAYLOAD
          chunk WTY_PAYLOAD
          assemble WTYP1_CHUNK...

        Profile (WENTUYI_HOME env, default ~/.config/wentuyi; all files 0600). These pick the
        protocol for you — prefer them over the raw commands above, and note the identity
        file IS your private key stored in the clear (no Keystore on desktop):
          init                                    create this machine's identity
          whoami                                  fingerprint / public key / identity QR
          import-identity [WTYB1 | --stdin]       (or WENTUYI_BACKUP env)
          set-passphrase [KEY | --stdin]          shared key for the legacy path
          peer-add --name NAME (--peer-public B64URL | --peer-qr WTYID1)
          peer-verify --peer NAME --code FULLCODE  after comparing the complete code out of band
          peer-list / peer-remove --peer NAME
          peer-reset --peer NAME                  open a fresh session after a desync
          send [--peer NAME] TEXT                 verified peer: ratchet if possible, else session key,
                                                  else shared passphrase (no --peer)
          receive WTY_PAYLOAD                     auto-detects protocol and verified sender
        All existing peers need explicit verification after the 256-bit authentication upgrade.

        WTY5 Double Ratchet, raw/stateless form (--state FILE holds the session and private
        key material — it is written 0600, treat it like the backup code):
          ratchet-init --backup WTYB1 (--peer-public B64URL | --peer-qr WTYID1) --state FILE
          ratchet-encrypt --state FILE TEXT
          ratchet-decrypt --backup WTYB1 (--peer-public B64URL | --peer-qr WTYID1) --state FILE WTY5_PAYLOAD
          ratchet-info --state FILE
        Only one side runs ratchet-init; the other's first ratchet-decrypt bootstraps from
        the epoch in the payload. If the peer resets, their newer epoch is adopted
        automatically; if you lose --state, run ratchet-init again and send one message.
        State from older CLI versions has no identity binding: reset it with peer-reset
        (profile) or ratchet-init (raw) once after upgrading; exchange a message to recover.
        """.trimIndent(),
    )
}
