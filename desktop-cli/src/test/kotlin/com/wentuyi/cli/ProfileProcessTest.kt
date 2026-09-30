package com.wentuyi.cli

import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** These are separate JVMs, so an in-process mutex alone cannot make the tests pass. */
class ProfileProcessTest {
    private val directory = Files.createTempDirectory("wentuyi-process-test")
    private val children = mutableListOf<Child>()

    @AfterTest fun cleanUp() {
        children.forEach { if (it.process.isAlive) it.process.destroyForcibly().waitFor() }
        directory.toFile().deleteRecursively()
    }

    @Test fun `concurrent profile sends each consume a distinct message key`() {
        val (sender, receiver, receiverState) = setupProfiles()
        val jobs = sender.transaction {
            (0 until 6).map { index ->
                start(sender.home, "send", "--peer", "bob", "message-$index")
            }.also { jobs ->
                jobs.forEach { it.release() }
                jobs.forEach { assertFalse(it.process.waitFor(200, TimeUnit.MILLISECONDS), "send bypassed profile lock") }
            }
        }
        val messages = jobs.map { it.result().success() }
        assertEquals(6, sender.loadRatchet("bob")!!.ns)
        // Decrypt every emitted message using one receiving state. Duplicate key/counter
        // use causes a failure here even if the ciphertext strings themselves differ.
        assertEquals((0 until 6).map { "message-$it" }.toSet(), messages.map {
            DoubleRatchet.decrypt(receiverState, it).toString(Charsets.UTF_8)
        }.toSet())
        assertTrue(Files.exists(receiver.home.resolve("identity")))
    }

    @Test fun `two processes cannot both emit the same received message`() {
        val (sender, receiver, _) = setupProfiles()
        val state = sender.loadRatchet("bob")!!
        val payload = DoubleRatchet.encrypt(state, "exactly once".toByteArray())
        sender.saveRatchet("bob", state)
        val jobs = receiver.transaction {
            List(2) { start(receiver.home, "receive", payload) }.also { jobs ->
                jobs.forEach { it.release() }
                jobs.forEach { assertFalse(it.process.waitFor(200, TimeUnit.MILLISECONDS), "receive bypassed profile lock") }
            }
        }
        val results = jobs.map { it.result() }
        assertEquals(1, results.count { it.code == 0 })
        assertEquals("exactly once", results.single { it.code == 0 }.out)
        assertEquals("", results.single { it.code != 0 }.out)
    }

    @Test fun `peer management waits for in-flight profile transaction`() {
        val (sender, _, _) = setupProfiles()
        val job = sender.transaction {
            start(sender.home, "peer-remove", "--peer", "bob").also {
                it.release()
                assertFalse(it.process.waitFor(400, TimeUnit.MILLISECONDS), "peer mutation bypassed profile lock")
                assertEquals(listOf("bob"), sender.peerNames())
            }
        }
        job.result().success()
        assertTrue(sender.peerNames().isEmpty())
        assertFalse(Files.exists(sender.home.resolve("peers/bob.ratchet")))
    }

    @Test fun `raw ratchet processes also serialize the whole read advance and write`() {
        val alice = KeyExchange.generateIdentity()
        val bob = KeyExchange.generateIdentity()
        val epoch = DoubleRatchet.newEpoch()
        val statePath = directory.resolve("raw.state")
        StoredRatchet(alice.publicKey, bob.publicKey, DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(alice, bob.publicKey, epoch), bob.publicKey, epoch)).write(statePath)
        val receiver = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(bob, alice.publicKey, epoch), bob, epoch)
        val jobs = SecretFiles.withStateLock(statePath) {
            (0 until 4).map { start(directory, "ratchet-encrypt", "--state", statePath.toString(), "raw-$it") }
                .also { jobs ->
                    jobs.forEach { it.release() }
                    jobs.forEach { assertFalse(it.process.waitFor(200, TimeUnit.MILLISECONDS), "raw send bypassed state lock") }
                }
        }
        assertEquals((0 until 4).map { "raw-$it" }.toSet(), jobs.map {
            DoubleRatchet.decrypt(receiver, it.result().success()).toString(Charsets.UTF_8)
        }.toSet())
        assertEquals(4, StoredRatchet.read(statePath).state.ns)
    }

    @Test fun `raw commands targeting profile state share the profile transaction`() {
        val (sender, _, receiver) = setupProfiles()
        val statePath = sender.home.resolve("peers/bob.ratchet")
        val jobs = sender.transaction {
            listOf(start(sender.home, "send", "--peer", "bob", "profile"),
                start(sender.home, "ratchet-encrypt", "--state", statePath.toString(), "raw"))
                .also { jobs ->
                    jobs.forEach { it.release() }
                    jobs.forEach { assertFalse(it.process.waitFor(200, TimeUnit.MILLISECONDS)) }
                }
        }
        assertEquals(setOf("profile", "raw"), jobs.map {
            DoubleRatchet.decrypt(receiver, it.result().success()).toString(Charsets.UTF_8)
        }.toSet())
        assertEquals(2, sender.loadRatchet("bob")!!.ns)
    }

    @Test fun `raw decrypt refuses state belonging to a different identity`() {
        val alice = KeyExchange.generateIdentity()
        val bob = KeyExchange.generateIdentity()
        val replacement = KeyExchange.generateIdentity()
        val epoch = DoubleRatchet.newEpoch()
        val sender = DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(alice, bob.publicKey, epoch), bob.publicKey, epoch)
        val receiver = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(bob, alice.publicKey, epoch), bob, epoch)
        val payload = DoubleRatchet.encrypt(sender, "old identity only".toByteArray())
        val statePath = directory.resolve("raw.state")
        StoredRatchet(bob.publicKey, alice.publicKey, receiver).write(statePath)
        val before = Files.readString(statePath)
        val job = start(directory, "ratchet-decrypt", "--state", statePath.toString(),
            "--backup", KeyExchange.encodeBackup(replacement), "--peer-public",
            Encoding.b64Url(alice.publicKey), payload)
        job.release()
        val result = job.result()
        assertTrue(result.code != 0)
        assertEquals("", result.out)
        assertTrue(result.error.contains("identity changed"))
        assertEquals(before, Files.readString(statePath))
    }

    @Test fun `real profile CLI preserves multiline unicode and boundary whitespace exactly`() {
        val (sender, receiver, _) = setupProfiles()
        val text = " \t中文 🦋\r\n第二行 \t\n\n"
        val send = start(sender.home, "send", "--peer", "bob", "--stdin", stdin = text)
        send.release()
        val payload = send.result().success()
        val receive = start(receiver.home, "receive", "--stdin", stdin = " \t$payload\r\n")
        receive.release()
        val result = receive.result()
        assertEquals(0, result.code, result.error)
        assertEquals(text, result.out)
    }

    @Test fun `real stateless CLI preserves plaintext instead of trimming stdin`() {
        val alice = KeyExchange.generateIdentity()
        val bob = KeyExchange.generateIdentity()
        for (text in listOf(" \t中文\r\n第二行 \t\n\n", " \t\r\n")) {
            val send = start(directory, "session-encrypt", "--backup", KeyExchange.encodeBackup(alice),
                "--peer-public", Encoding.b64Url(bob.publicKey), "--stdin", stdin = text)
            send.release()
            val payload = send.result().success()
            val receive = start(directory, "session-decrypt", "--backup", KeyExchange.encodeBackup(bob),
                "--peer-public", Encoding.b64Url(alice.publicKey), "--stdin", stdin = payload + "\n")
            receive.release()
            val result = receive.result()
            assertEquals(0, result.code, result.error)
            assertEquals(text, result.out)
        }
    }

    @Test fun `CLI profile and raw resets advance beyond a stored future epoch`() {
        val (sender, _, _) = setupProfiles()
        val current = sender.loadRatchet("bob")!!
        val future = System.currentTimeMillis() + 86_400_000L
        val identity = sender.loadIdentity()
        val peer = sender.peerPublicKey("bob")
        sender.saveRatchet("bob", DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(identity, peer, future), peer, future))
        val reset = start(sender.home, "peer-reset", "--peer", "bob")
        reset.release()
        reset.result().success()
        assertEquals(future + 1, sender.loadRatchet("bob")!!.epoch)
        val path = sender.home.resolve("peers/bob.ratchet")
        val rawReset = start(sender.home, "ratchet-init", "--backup", KeyExchange.encodeBackup(identity),
            "--peer-public", Encoding.b64Url(peer), "--state", path.toString())
        rawReset.release()
        rawReset.result().success()
        assertEquals(future + 2, sender.loadRatchet("bob")!!.epoch)
        // Legacy unbound serialization is also retired rather than silently reusing its epoch.
        current.epoch = future + 100
        Files.writeString(path, com.wentuyi.protocol.RatchetStateCodec.encodeText(current))
        val legacyReset = start(sender.home, "peer-reset", "--peer", "bob")
        legacyReset.release()
        legacyReset.result().success()
        assertEquals(future + 101, sender.loadRatchet("bob")!!.epoch)
    }

    private fun setupProfiles(): Triple<Profile, Profile, DoubleRatchet.State> {
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
        val receivingState = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(bob, alice.publicKey, epoch), bob, epoch)
        return Triple(sender, receiver, receivingState)
    }

    private fun start(home: Path, vararg args: String, stdin: String? = null): Child {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(listOf(java, "-cp", System.getProperty("wentuyi.test.classpath"),
            "com.wentuyi.cli.ProfileProcessWorker") + args)
        builder.environment().apply {
            put("WENTUYI_HOME", home.toString())
            remove("WENTUYI_BACKUP")
            remove("WENTUYI_PASSPHRASE")
        }
        val process = builder.start()
        val child = Child(process, process.errorStream.bufferedReader(), stdin)
        children += child
        assertEquals("ready", child.errors.readLine(), "child JVM failed to start")
        return child
    }

    private class Child(val process: Process, val errors: BufferedReader, val stdin: String?) {
        fun release() { process.outputStream.use {
            it.write(1)
            if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8))
        } }
        fun result(): Result {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child JVM timed out")
            return Result(process.exitValue(), process.inputStream.bufferedReader(Charsets.UTF_8).readText(), errors.readText())
        }
    }

    private data class Result(val code: Int, val out: String, val error: String) {
        fun success(): String { assertEquals(0, code, error); return out.trim() }
    }
}

/** Gate after JVM startup, immediately before entering the actual CLI command. */
object ProfileProcessWorker {
    @JvmStatic fun main(args: Array<String>) {
        System.err.println("ready")
        check(System.`in`.read() == 1)
        com.wentuyi.cli.main(args)
    }
}
