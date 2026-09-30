package com.wentuyi.app

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
class EncryptLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun draftRecipientAndCompletedCiphertextSurviveActivityRecreation() = withPeer { _, _, contact ->
        ActivityScenario.launch<EncryptActivity>(EncryptActivity.intentFor(context, "最初导入的文字")).use { scenario ->
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<EditText>().single().setText(DRAFT)
                // Select through the same holder API used by the recipient picker.
                val operation = activity.onRetainNonConfigurationInstance() as EncryptOperation
                operation.select(contact.fingerprint)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val all = views(activity.window.decorView)
                assertEquals(DRAFT, all.filterIsInstance<EditText>().single().text.toString())
                assertTrue(all.filterIsInstance<Button>().any { it.text.toString() == contact.name })
                all.filterIsInstance<Button>().single { it.text.toString() == "加密" }.performClick()
            }
            val ciphertext = awaitCiphertext(scenario)
            val persisted = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            scenario.recreate()
            assertEquals(ciphertext, awaitCiphertext(scenario))
            scenario.onActivity { activity ->
                assertEquals("", views(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
            }
            assertEquals(persisted, WentuyiSettings.loadRatchet(context, contact.fingerprint))
        }
    }

    @Test fun rotationAfterSendStateCommitKeepsTheOriginalCiphertextAndDoesNotSendTwice() = withPeer { me, peer, contact ->
        val committed = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val displayed = CountDownLatch(1)
        val calls = AtomicInteger()
        val target = SendTarget.Contact(contact, me)
        lateinit var operation: EncryptOperation
        var ciphertext: String? = null
        instrumentation.runOnMainSync {
            operation = EncryptOperation(context, DRAFT) { appContext, recipient, plaintext ->
                calls.incrementAndGet()
                val result = MessageEncryptor.encryptText(appContext, recipient, plaintext)
                committed.countDown()
                check(releaseWorker.await(10, TimeUnit.SECONDS))
                result
            }
            operation.select(contact.fingerprint)
            operation.attach { }
            operation.encrypt(target)
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            val persisted = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            instrumentation.runOnMainSync {
                operation.detach()
                operation.attach {
                    val state = operation.state
                    if (state is EncryptOperation.State.Success) {
                        ciphertext = state.encrypted.payload
                        assertEquals("", operation.draft)
                        displayed.countDown()
                    }
                }
                operation.encrypt(target) // Duplicate tap after rebinding must be ignored.
            }
            releaseWorker.countDown()
            assertTrue(displayed.await(10, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(persisted, WentuyiSettings.loadRatchet(context, contact.fingerprint))
            val payload = ciphertext!!
            val epoch = DoubleRatchet.peekEpoch(payload)!!
            val receiver = DoubleRatchet.initReceiver(
                DoubleRatchet.initialRootKey(peer, me.publicKey, epoch), peer, epoch)
            assertEquals(DRAFT, String(DoubleRatchet.decrypt(receiver, payload), Charsets.UTF_8))
        } finally {
            releaseWorker.countDown()
            instrumentation.runOnMainSync { operation.close() }
        }
    }

    private fun withPeer(block: (KeyExchange.Identity, KeyExchange.Identity, KeyExchange.Contact) -> Unit) {
        val me = KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val contact = KeyExchange.Contact("composer-rotation-peer", peer.publicKey, verified = true)
        KeyExchange.saveContact(context, contact)
        try {
            RatchetSession.restart(context, me, contact)
            block(me, peer, contact)
        } finally { KeyExchange.removeContact(context, contact.fingerprint) }
    }

    private fun awaitCiphertext(scenario: ActivityScenario<EncryptActivity>): String {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var payload: String? = null
            scenario.onActivity { activity ->
                payload = views(activity.window.decorView).filterIsInstance<TextView>()
                    .map { it.text.toString() }.firstOrNull { it.startsWith(DoubleRatchet.PREFIX_V5) }
            }
            payload?.let { return it }
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("encrypted result did not appear")
    }

    private fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i)))
    }

    private companion object { const val DRAFT = "只有文图易输入页应持有的草稿\n屏幕旋转后继续编辑。" }
}
