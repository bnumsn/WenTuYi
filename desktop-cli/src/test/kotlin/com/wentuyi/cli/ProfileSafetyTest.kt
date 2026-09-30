package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.PayloadLimits
import com.wentuyi.protocol.RatchetStateCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class ProfileSafetyTest {
    private val directory = Files.createTempDirectory("wentuyi-profile-test")

    @AfterTest fun cleanUp() { directory.toFile().deleteRecursively() }

    private fun pair(): Pair<Profile, Profile> {
        val sender = Profile(directory.resolve("sender"))
        val receiver = Profile(directory.resolve("receiver"))
        val alice = sender.createIdentity()
        val bob = receiver.createIdentity()
        sender.addPeer("bob", bob.publicKey)
        receiver.addPeer("alice", alice.publicKey)
        sender.verifyPeer("bob", KeyExchange.shortAuthString(alice, bob.publicKey))
        receiver.verifyPeer("alice", KeyExchange.shortAuthString(bob, alice.publicKey))
        val epoch = DoubleRatchet.newEpoch()
        sender.saveRatchet("bob", DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(alice, bob.publicKey, epoch), bob.publicKey, epoch))
        return sender to receiver
    }

    @Test fun `changing peer prevents old recipient from decrypting future messages`() {
        val (sender, oldReceiver) = pair()
        val first = output { ProfileCommands.send(sender, "bob", "before replacement") }
        assertEquals("before replacement", output { ProfileCommands.receive(oldReceiver, first) })
        val statePath = sender.home.resolve("peers/bob.ratchet")
        val before = Files.readString(statePath)
        sender.addPeer("bob", oldReceiver.loadIdentity().publicKey)
        assertEquals(before, Files.readString(statePath), "re-adding an unchanged key preserves the chain")

        val newReceiver = Profile(directory.resolve("replacement"))
        newReceiver.createIdentity()
        newReceiver.addPeer("alice", sender.loadIdentity().publicKey)
        sender.addPeer("bob", newReceiver.loadIdentity().publicKey)
        assertNull(sender.loadRatchet("bob"))
        sender.verifyPeer("bob", KeyExchange.shortAuthString(sender.loadIdentity(), newReceiver.loadIdentity().publicKey))
        newReceiver.verifyPeer("alice", KeyExchange.shortAuthString(newReceiver.loadIdentity(), sender.loadIdentity().publicKey))
        val payload = output { ProfileCommands.send(sender, "bob", "only new recipient") }
        assertFails { ProfileCommands.receive(oldReceiver, payload) }
        assertEquals("only new recipient", output { ProfileCommands.receive(newReceiver, payload) })
    }

    @Test fun `identity replacement clears every chain but same identity preserves it`() {
        val (sender, _) = pair()
        val state = assertNotNull(sender.loadRatchet("bob"))
        sender.addPeer("second", KeyExchange.generateIdentity().publicKey)
        sender.saveRatchet("second", state)
        val before = Files.readString(sender.home.resolve("peers/bob.ratchet"))
        sender.importIdentity(KeyExchange.encodeBackup(sender.loadIdentity()))
        assertEquals(before, Files.readString(sender.home.resolve("peers/bob.ratchet")))
        sender.importIdentity(KeyExchange.encodeBackup(KeyExchange.generateIdentity()))
        assertNull(sender.loadRatchet("bob"))
        assertNull(sender.loadRatchet("second"))
    }

    @Test fun `stored state cannot be rebound by editing a public key or identity file`() {
        val (sender, _) = pair()
        val identityFile = sender.home.resolve("identity")
        val originalIdentity = Files.readString(identityFile)
        Files.writeString(identityFile, KeyExchange.encodeBackup(KeyExchange.generateIdentity()))
        assertFailsWith<IllegalArgumentException> { sender.loadRatchet("bob") }
        Files.writeString(identityFile, originalIdentity)
        Files.writeString(sender.home.resolve("peers/bob.pub"),
            Encoding.b64Url(KeyExchange.generateIdentity().publicKey))
        assertFailsWith<IllegalArgumentException> { sender.loadRatchet("bob") }
    }

    @Test fun `legacy and corrupted sessions require explicit recovery`() {
        val (sender, _) = pair()
        val state = assertNotNull(sender.loadRatchet("bob"))
        val path = sender.home.resolve("peers/bob.ratchet")
        Files.writeString(path, RatchetStateCodec.encodeText(state))
        val legacy = assertFailsWith<IllegalArgumentException> { sender.loadRatchet("bob") }
        assertTrue(legacy.message.orEmpty().contains("peer-reset"))
        Files.writeString(path, "corrupted")
        assertFailsWith<IllegalArgumentException> { sender.loadRatchet("bob") }
    }

    @Test fun `failed send persistence emits nothing and preserves previous state`() {
        val (sender, _) = pair()
        val path = sender.home.resolve("peers/bob.ratchet")
        val before = Files.readAllBytes(path)
        readOnlyDirectory(path.parent) {
            assertEquals("", output {
                assertFails { ProfileCommands.send(sender, "bob", "must not escape") }
            })
            assertContentEquals(before, Files.readAllBytes(path))
        }
        val payload = output { ProfileCommands.send(sender, "bob", "retry safely") }
        assertTrue(payload.startsWith("WTY5:"))
        assertEquals(1, sender.loadRatchet("bob")!!.ns)
    }

    @Test fun `failed receive persistence emits nothing and message remains usable exactly once`() {
        val (sender, receiver) = pair()
        val first = output { ProfileCommands.send(sender, "bob", "first") }
        output { ProfileCommands.receive(receiver, first) }
        val next = output { ProfileCommands.send(sender, "bob", "second") }
        val path = receiver.home.resolve("peers/alice.ratchet")
        val before = Files.readAllBytes(path)
        readOnlyDirectory(path.parent) {
            assertEquals("", output { assertFails { ProfileCommands.receive(receiver, next) } })
            assertContentEquals(before, Files.readAllBytes(path))
        }
        assertEquals("second", output { ProfileCommands.receive(receiver, next) })
        assertFails { ProfileCommands.receive(receiver, next) }
    }

    @Test fun `secret replacement keeps owner-only permissions and leaves no temporary files`() {
        val (sender, _) = pair()
        assumeTrue(directory.fileSystem.supportedFileAttributeViews().contains("posix"))
        val path = sender.home.resolve("peers/bob.ratchet")
        output { ProfileCommands.send(sender, "bob", "permissions") }
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        Files.list(path.parent).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    @Test fun `stdin permits maximum payload with CRLF and bounds oversized streams`() {
        val payload = "x".repeat(PayloadLimits.MAX_PAYLOAD_CHARS)
        assertEquals(payload, readInputText(ByteArrayInputStream((payload + "\r\n").toByteArray())))
        assertFailsWith<IllegalArgumentException> {
            readInputText(ByteArrayInputStream(ByteArray(PayloadLimits.MAX_PAYLOAD_CHARS + 3) { 120 }))
        }
        assertEquals("文图易", readInputText(ByteArrayInputStream("文图易\n".toByteArray())))
    }

    @Test fun `new and legacy peers remain blocked until full version two code is verified`() {
        val profile = Profile(directory.resolve("auth"))
        val identity = profile.createIdentity()
        val peer = KeyExchange.generateIdentity()
        profile.addPeer("bob", peer.publicKey)
        assertTrue(!profile.isPeerVerified("bob"))
        assertEquals("", output { assertFails { ProfileCommands.send(profile, "bob", "blocked") } })
        assertFails { profile.verifyPeer("bob", "12345678") }
        val code = KeyExchange.shortAuthString(identity, peer.publicKey)
        assertFails { profile.verifyPeer("bob", "0".repeat(64)) }
        profile.verifyPeer("bob", code.lowercase().replace(" ", "\n"))
        assertTrue(profile.isPeerVerified("bob"))
        profile.addPeer("bob", peer.publicKey)
        assertTrue(profile.isPeerVerified("bob"), "the same identity keeps a current verification")
        val auth = profile.home.resolve("peers/bob.auth")
        Files.writeString(auth, Files.readString(auth).replace("WTYA2", "WTYA1"))
        assertTrue(!profile.isPeerVerified("bob"), "legacy verification must never carry forward")
        assertFails { ProfileCommands.send(profile, "bob", "legacy blocked") }
        profile.verifyPeer("bob", code)
        Files.writeString(profile.home.resolve("peers/bob.pub"),
            Encoding.b64Url(KeyExchange.generateIdentity().publicKey))
        assertTrue(!profile.isPeerVerified("bob"), "record is bound to the peer key")
        Files.writeString(profile.home.resolve("peers/bob.pub"), Encoding.b64Url(peer.publicKey))
        Files.writeString(profile.home.resolve("identity"), KeyExchange.encodeBackup(KeyExchange.generateIdentity()))
        assertTrue(!profile.isPeerVerified("bob"), "record is bound to the local identity")
    }

    @Test fun `changing identity or contact invalidates authentication and old sessions`() {
        val (sender, _) = pair()
        sender.addPeer("bob", KeyExchange.generateIdentity().publicKey)
        assertTrue(!sender.isPeerVerified("bob"))
        sender.verifyPeer("bob", KeyExchange.shortAuthString(sender.loadIdentity(), sender.peerPublicKey("bob")))
        sender.importIdentity(KeyExchange.encodeBackup(KeyExchange.generateIdentity()))
        assertTrue(!sender.isPeerVerified("bob"))
        sender.removePeer("bob")
        assertTrue(!Files.exists(sender.home.resolve("peers/bob.auth")))
    }

    @Test fun `plaintext stdin preserves every boundary byte while wire stdin trims`() {
        for (text in listOf(" \t中文\r\n第二行 \t\r\n\n", " \t\n")) {
            assertEquals(text, readPlaintext(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        }
        assertEquals("WTY5:payload", readInputText(ByteArrayInputStream(" \tWTY5:payload\r\n".toByteArray())))
        assertFailsWith<IllegalArgumentException> { readPlaintext(ByteArrayInputStream(byteArrayOf())) }
    }

    @Test fun `valid identity import recovers a corrupt identity and invalidates prior trust`() {
        val (sender, _) = pair()
        val replacement = KeyExchange.generateIdentity()
        Files.writeString(sender.home.resolve("identity"), "corrupted")
        sender.importIdentity(KeyExchange.encodeBackup(replacement))
        assertContentEquals(replacement.publicKey, sender.loadIdentity().publicKey)
        assertNull(sender.loadRatchet("bob"))
        assertTrue(!sender.isPeerVerified("bob"))
    }

    @Test fun `peer list exposes full code even when its session needs reset`() {
        val (sender, _) = pair()
        Files.writeString(sender.home.resolve("peers/bob.ratchet"), "corrupted")
        val listed = output { ProfileCommands.peerList(sender) }
        assertTrue(listed.contains(KeyExchange.shortAuthString(sender.loadIdentity(), sender.peerPublicKey("bob"))))
        assertTrue(listed.contains("needs-reset"))
    }

    private fun readOnlyDirectory(path: Path, block: () -> Unit) {
        assumeTrue(path.fileSystem.supportedFileAttributeViews().contains("posix"))
        val permissions = Files.getPosixFilePermissions(path)
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-x------"))
            assumeTrue("requires an unprivileged process", !Files.isWritable(path))
            block()
        } finally {
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private fun output(block: () -> Unit): String {
        val previous = System.out
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
            block()
        } finally {
            System.setOut(previous)
        }
        return bytes.toString(Charsets.UTF_8).trim()
    }
}
