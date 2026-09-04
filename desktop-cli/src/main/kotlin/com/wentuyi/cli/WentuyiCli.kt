package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.PayloadChunks
import com.wentuyi.protocol.RatchetStateCodec
import com.wentuyi.protocol.SecurePayloadCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

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
            val payload = resolveText(args.drop(1), setOf("--passphrase"))
            println(SecurePayloadCodec.decryptPayload(payload, passphrase))
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
            val payload = resolveText(args.drop(1), setOf("--out-dir", "--prefix"))
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
                    System.`in`.readBytes().toString(Charsets.UTF_8).trim()
                        .ifEmpty { throw IllegalArgumentException("empty stdin") }
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
                val payload = resolveText(args.drop(1), setOf("--backup", "--peer-public", "--peer-qr"))
                println(SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, secret).text())
            } finally {
                com.wentuyi.protocol.CryptoUtils.wipe(secret)
            }
        }
        // ─── WTY5 Double Ratchet ──────────────────────────────────────────────────
        // The Android app sends WTY5 to every verified contact by default, so without these
        // a desktop peer simply cannot read messages from someone who verified them — the
        // more carefully the two users set the contact up, the more broken it was.
        // Unlike the stateless commands above, a ratchet needs somewhere to keep the session:
        // --state names that file, and every command rewrites it after a successful step.
        "ratchet-init" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val peer = peerPublic(args)
            val statePath = Path.of(option(args, "--state"))
            val epoch = DoubleRatchet.newEpoch()
            val state = DoubleRatchet.initSender(
                DoubleRatchet.initialRootKey(identity, peer, epoch), peer, epoch)
            writeState(statePath, state)
            println("epoch=$epoch")
            println("state=${statePath.toAbsolutePath()}")
        }
        "ratchet-encrypt" -> {
            val statePath = Path.of(option(args, "--state"))
            val state = readState(statePath)
            val text = resolveText(args.drop(1), setOf("--state"))
            val payload = DoubleRatchet.encrypt(state, text.toByteArray(Charsets.UTF_8))
            writeState(statePath, state)   // only after encrypt() advanced it
            println(payload)
        }
        "ratchet-decrypt" -> {
            val identity = KeyExchange.decodeBackup(resolveBackup(args))
            val peer = peerPublic(args)
            val statePath = Path.of(option(args, "--state"))
            val payload = resolveText(
                args.drop(1), setOf("--backup", "--peer-public", "--peer-qr", "--state"))
            val headerEpoch = DoubleRatchet.peekEpoch(payload)
                ?: throw IllegalArgumentException("not a WTY5 ratchet payload")
            val stored = if (Files.exists(statePath)) readState(statePath) else null

            // Same three-way rule as the Android app: use the session we hold for this epoch;
            // adopt a strictly newer one (the peer reset); refuse a retired one so a dead
            // session's ciphertext can't be replayed into the live one.
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
            writeState(statePath, state)   // only after the AEAD tag verified
            println(String(plain, Charsets.UTF_8))
        }
        "ratchet-info" -> {
            val state = readState(Path.of(option(args, "--state")))
            println("epoch=${state.epoch}")
            println("sending=${state.cks != null}")
            println("receiving=${state.ckr != null}")
            println("ns=${state.ns} nr=${state.nr} pn=${state.pn} skipped=${state.skipped.size}")
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

/**
 * Text/payload from `--stdin` (read whole stdin, trimmed) when present, else the positional
 * args. stdin keeps plaintext off the command line too — bridges pipe the message in.
 */
private fun resolveText(args: List<String>, optionNames: Set<String>): String =
    if (args.contains("--stdin")) {
        System.`in`.readBytes().toString(Charsets.UTF_8).trim()
            .ifEmpty { throw IllegalArgumentException("empty stdin") }
    } else {
        restAfterOptions(args, optionNames)
    }

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
private fun writeState(path: Path, state: DoubleRatchet.State) {
    path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    Files.write(path, RatchetStateCodec.encodeText(state).toByteArray(Charsets.UTF_8))
    runCatching {
        Files.setPosixFilePermissions(
            path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }  // no-op on filesystems without POSIX perms (Windows)
}

private fun readState(path: Path): DoubleRatchet.State {
    if (!Files.exists(path)) throw IllegalArgumentException("no ratchet state at $path (run ratchet-init)")
    return RatchetStateCodec.decodeText(Files.readString(path))
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

        WTY5 Double Ratchet (forward secrecy; --state FILE holds the session and private
        key material — it is written 0600, treat it like the backup code):
          ratchet-init --backup WTYB1 (--peer-public B64URL | --peer-qr WTYID1) --state FILE
          ratchet-encrypt --state FILE TEXT
          ratchet-decrypt --backup WTYB1 (--peer-public B64URL | --peer-qr WTYID1) --state FILE WTY5_PAYLOAD
          ratchet-info --state FILE
        Only one side runs ratchet-init; the other's first ratchet-decrypt bootstraps from
        the epoch in the payload. If the peer resets, their newer epoch is adopted
        automatically; if you lose --state, run ratchet-init again and send one message.
        """.trimIndent(),
    )
}
