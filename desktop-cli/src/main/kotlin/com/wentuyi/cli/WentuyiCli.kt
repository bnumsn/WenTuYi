package com.wentuyi.cli

import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.PayloadChunks
import com.wentuyi.protocol.SecurePayloadCodec
import java.nio.file.Path

fun main(args: Array<String>) {
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
            val identity = KeyExchange.decodeBackup(args.drop(1).joinToString(""))
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
          decrypt-text --passphrase KEY WTY3_PAYLOAD
          plain-image --out FILE.png TEXT
          encrypted-qr --passphrase KEY --out-dir DIR [--prefix NAME] TEXT
          payload-qr --out-dir DIR [--prefix NAME] WTY3_PAYLOAD
          gen-identity [--name NAME]
          restore-backup WTYB1_BACKUP
          sas --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT)
          session-encrypt --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT) TEXT
          session-decrypt --backup WTYB1_BACKUP (--peer-public B64URL | --peer-qr WTYID1_TEXT) WTY3_PAYLOAD
          chunk WTY3_PAYLOAD
          assemble WTYP1_CHUNK...
        """.trimIndent(),
    )
}
