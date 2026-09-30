package com.wentuyi.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DecryptLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun completedWty5ResultSurvivesActivityRecreationWithoutConsumingAgain() = withMessage { payload, contact ->
        val intent = Intent(context, DecryptActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, payload)
        ActivityScenario.launch<DecryptActivity>(intent).use { scenario ->
            awaitResult(scenario)
            val consumed = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            scenario.recreate()
            awaitResult(scenario)
            assertEquals(consumed, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        }
    }

    @Test fun detachedViewDoesNotLoseAResultAfterTheRatchetWasCommitted() = withMessage { payload, contact ->
        val committed = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val displayed = CountDownLatch(1)
        val calls = AtomicInteger()
        lateinit var operation: DecryptOperation
        instrumentation.runOnMainSync {
            operation = DecryptOperation(context) { appContext, input ->
                calls.incrementAndGet()
                val result = MessageDecryptor.decrypt(appContext, input)
                check(result is MessageDecryptor.Result.Success)
                committed.countDown()
                check(releaseWorker.await(10, TimeUnit.SECONDS))
                result
            }
            operation.attach { }
            operation.decryptText(payload)
        }
        try {
            assertTrue("receive state reached disk", committed.await(10, TimeUnit.SECONDS))
            val consumed = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            instrumentation.runOnMainSync {
                // This is the critical rotation window: crypto already advanced the
                // ratchet, but no view has seen its result yet.
                operation.detach()
                operation.attach { state ->
                    if (state is DecryptOperation.State.Success) {
                        assertEquals(PLAINTEXT, state.result.lastPlainText)
                        displayed.countDown()
                    }
                }
                operation.decryptText(payload) // An accidental duplicate is coalesced.
            }
            releaseWorker.countDown()
            assertTrue("new view receives original result", displayed.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { operation.decryptText(payload) }
            assertEquals(1, calls.get())
            assertEquals(consumed, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        } finally {
            releaseWorker.countDown()
            instrumentation.runOnMainSync { operation.close() }
        }
    }

    @Test fun scannerKeepsTheConsumedResultAcrossRecreation() = withMessage { payload, contact ->
        ActivityScenario.launch(ScanActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val operation = activity.onRetainNonConfigurationInstance() as DecryptOperation
                operation.decryptQrTexts(listOf(payload))
            }
            awaitScanResult(scenario)
            val consumed = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            scenario.recreate()
            awaitScanResult(scenario)
            assertEquals(consumed, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        }
    }

    @Test fun editorSwitchAfterReceiveCommitRetainsTheResultAndItsOriginalTarget() = withMessage { payload, contact ->
        val committed = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val displayed = CountDownLatch(1)
        val original = KeyboardDecryptSession.Anchor("original.chat", 0, 1)
        val other = KeyboardDecryptSession.Anchor("another.chat", 0, 2)
        val input = KeyboardDecryptSession.Input.Payload(payload, fromInputBox = true)
        val calls = AtomicInteger()
        lateinit var session: KeyboardDecryptSession
        instrumentation.runOnMainSync {
            session = KeyboardDecryptSession(context) { appContext, source ->
                calls.incrementAndGet()
                val result = MessageDecryptor.decrypt(appContext, source)
                check(result is MessageDecryptor.Result.Success)
                committed.countDown()
                check(releaseWorker.await(10, TimeUnit.SECONDS))
                result
            }
            session.operation.attach { }
            session.submit(original, input)
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            val consumed = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            instrumentation.runOnMainSync {
                // Finish/restart input detaches the visible panel. It must not cancel
                // an accepted receive or bind its eventual result to another editor.
                session.operation.detach()
                assertTrue(session.canWriteTo(original))
                assertTrue(!session.canWriteTo(other))
                assertTrue(!session.canWriteTo(original.copy(session = 2)))
                session.operation.attach { state ->
                    if (state is DecryptOperation.State.Success) {
                        assertEquals(PLAINTEXT, state.result.lastPlainText)
                        displayed.countDown()
                    }
                }
            }
            releaseWorker.countDown()
            assertTrue(displayed.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                session.submit(other, input) // Viewing the same consumed ciphertext never rebinds it.
                assertEquals(original, session.anchor)
                assertTrue(!session.canWriteTo(other))
                assertEquals(PLAINTEXT, (session.operation.state as DecryptOperation.State.Success).result.lastPlainText)
            }
            assertEquals(1, calls.get())
            assertEquals(consumed, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        } finally {
            releaseWorker.countDown()
            instrumentation.runOnMainSync { session.operation.close() }
        }
    }

    private fun awaitScanResult(scenario: ActivityScenario<ScanActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var found = false
            scenario.onActivity { found = containsText(it.window.decorView, PLAINTEXT) }
            if (found) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("scanner result did not appear")
    }

    private fun withMessage(block: (String, KeyExchange.Contact) -> Unit) {
        val me = KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val contact = KeyExchange.Contact("rotation-test", peer.publicKey, verified = true)
        KeyExchange.saveContact(context, contact)
        try {
            val epoch = DoubleRatchet.newEpoch()
            val sender = DoubleRatchet.initSender(
                DoubleRatchet.initialRootKey(peer, me.publicKey, epoch), me.publicKey, epoch)
            block(DoubleRatchet.encrypt(sender, PLAINTEXT.toByteArray()), contact)
        } finally { KeyExchange.removeContact(context, contact.fingerprint) }
    }

    private fun awaitResult(scenario: ActivityScenario<DecryptActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var found = false
            scenario.onActivity { activity ->
                found = containsText(activity.window.decorView, PLAINTEXT)
            }
            if (found) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("decrypted result did not appear")
    }

    private fun containsText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
    }

    private companion object { const val PLAINTEXT = "屏幕重建后仍能显示这条一次性消息" }
}
