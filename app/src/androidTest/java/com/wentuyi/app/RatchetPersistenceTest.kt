package com.wentuyi.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RatchetPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedSaveReturnsNoCiphertextAndLeavesDurableSendStateUsable() = withPeer { me, peer, contact ->
        RatchetSession.restart(context, me, contact)
        val before = WentuyiSettings.loadRatchet(context, contact.fingerprint)!!
        expectStorageFailure {
            RatchetSession.encryptText(failingWrites(context), me, contact, "must not escape")
        }
        assertEquals(before, WentuyiSettings.loadRatchet(context, contact.fingerprint))

        val payload = RatchetSession.encryptText(context, me, contact, "saved before output")!!
        val epoch = DoubleRatchet.peekEpoch(payload)!!
        val receiver = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(peer, me.publicKey, epoch), peer, epoch)
        assertEquals("saved before output", String(DoubleRatchet.decrypt(receiver, payload), Charsets.UTF_8))
    }

    @Test fun failedReceiveSaveExposesNoPlaintextAndRetryStillWorks() = withPeer { me, peer, contact ->
        val epoch = DoubleRatchet.newEpoch()
        val sender = DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(peer, me.publicKey, epoch), me.publicKey, epoch)
        val payload = DoubleRatchet.encrypt(sender, "private receive".toByteArray())
        expectStorageFailure { RatchetSession.tryDecrypt(failingWrites(context), me, contact, payload) }
        assertEquals(null, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        val received = RatchetSession.tryDecrypt(context, me, contact, payload)
        assertTrue(received is RatchetSession.Attempt.Ok)
        assertEquals("private receive", String((received as RatchetSession.Attempt.Ok).plaintext, Charsets.UTF_8))
    }

    @Test fun concurrentSendsEachPersistAUniqueMessageStep() = withPeer { me, peer, contact ->
        RatchetSession.restart(context, me, contact)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { index ->
                pool.submit(Callable { index to RatchetSession.encryptText(context, me, contact, "message-$index")!! })
            }
            val messages = futures.map { it.get(20, TimeUnit.SECONDS) }
            val epoch = DoubleRatchet.peekEpoch(messages.first().second)!!
            val receiver = DoubleRatchet.initReceiver(
                DoubleRatchet.initialRootKey(peer, me.publicKey, epoch), peer, epoch)
            for ((index, payload) in messages) {
                assertEquals("message-$index", String(DoubleRatchet.decrypt(receiver, payload), Charsets.UTF_8))
            }
            val saved = DoubleRatchet.deserialize(WentuyiSettings.loadRatchet(context, contact.fingerprint)!!)
            assertEquals(8, saved.ns)
        } finally { pool.shutdownNow() }
    }

    @Test fun staleVerifiedContactCannotSendAfterUnverification() = withPeer { me, _, contact ->
        KeyExchange.setContactVerified(context, contact.fingerprint, false)
        try {
            RatchetSession.encryptText(context, me, contact, "stale UI target")
            fail("queued verified snapshot must be rechecked")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("验证"))
        }
        assertEquals(null, WentuyiSettings.loadRatchet(context, contact.fingerprint))
    }

    @Test fun resetAdvancesAnEpochCreatedByAFasterPeerClock() = withPeer { me, peer, contact ->
        val futureEpoch = System.currentTimeMillis() + 300_000
        val state = DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(me, peer.publicKey, futureEpoch), peer.publicKey, futureEpoch)
        WentuyiSettings.saveRatchet(context, contact.fingerprint, DoubleRatchet.serialize(state))
        RatchetSession.restart(context, me, contact)
        val reset = DoubleRatchet.deserialize(WentuyiSettings.loadRatchet(context, contact.fingerprint)!!)
        assertTrue(reset.epoch > futureEpoch)
        val payload = RatchetSession.encryptText(context, me, contact, "reset after faster clock")!!
        val receiver = DoubleRatchet.initReceiver(
            DoubleRatchet.initialRootKey(peer, me.publicKey, reset.epoch), peer, reset.epoch)
        assertEquals("reset after faster clock", String(DoubleRatchet.decrypt(receiver, payload), Charsets.UTF_8))
    }

    private fun withPeer(block: (KeyExchange.Identity, KeyExchange.Identity, KeyExchange.Contact) -> Unit) {
        val me = KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val contact = KeyExchange.Contact("persistence-test", peer.publicKey, verified = true)
        KeyExchange.saveContact(context, contact)
        try { block(me, peer, contact) } finally { KeyExchange.removeContact(context, contact.fingerprint) }
    }

    private fun expectStorageFailure(block: () -> Any?) {
        try { block(); fail("failed durable save must abort output") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("保存失败")) }
    }

    /** Real reads/Keystore crypto, with a disk writer that refuses to commit. */
    private fun failingWrites(base: Context): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = base.getSharedPreferences(name, mode)
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean = false
                    }
                }
            }
        }
    }
}
