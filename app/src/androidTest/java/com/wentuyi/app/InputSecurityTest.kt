package com.wentuyi.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputSecurityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearResults() { ScreenDecryptStore.clear(context) }

    @Test fun selectedIdentitySurvivesReorderingAndRemovalOfAnotherPeer() {
        val alice = KeyExchange.Contact("Alice", ByteArray(32) { 1 }, true)
        val bob = KeyExchange.Contact("Bob", ByteArray(32) { 2 }, true)
        val selection = SendTargetSelection().apply { select(bob.fingerprint) }
        assertEquals(bob, selection.contact(listOf(alice, bob)))
        assertEquals(bob, selection.contact(listOf(bob, alice)))
        assertEquals(bob, selection.contact(listOf(bob)))
        assertEquals(1, selection.indexIn(listOf(bob)))
    }

    @Test fun removedIdentityDoesNotTurnIntoAnotherPeerOrSharedKey() {
        val alice = KeyExchange.Contact("Alice", ByteArray(32) { 1 }, true)
        val replacement = KeyExchange.Contact("Alice", ByteArray(32) { 2 }, true)
        val selection = SendTargetSelection().apply { select(alice.fingerprint) }
        assertNull(selection.contact(listOf(replacement)))
        assertFalse(selection.isShared)
        assertEquals(-1, selection.indexIn(listOf(replacement)))
    }

    @Test fun oldScreenRequestCannotCompleteANewerRequest() {
        val old = ScreenDecryptStore.begin()
        val current = ScreenDecryptStore.begin()
        ScreenDecryptStore.save(result(old, "old plaintext"))
        assertNull(ScreenDecryptStore.consume(current))
        ScreenDecryptStore.save(result(current, "current plaintext"))
        assertNull(ScreenDecryptStore.consume(old))
        assertEquals("current plaintext", ScreenDecryptStore.consume(current)
            ?.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT))
        assertNull(ScreenDecryptStore.consume(current))
    }

    @Test fun unsolicitedAndCancelledResultsAreNeverRetained() {
        ScreenDecryptStore.clear(context)
        ScreenDecryptStore.save(result("forged", "forged plaintext"))
        assertNull(ScreenDecryptStore.consume("forged"))
        val request = ScreenDecryptStore.begin()
        ScreenDecryptStore.clear(context)
        ScreenDecryptStore.save(result(request, "late plaintext"))
        assertNull(ScreenDecryptStore.consume(request))
    }

    @Test fun oldPublicBroadcastCannotInjectAScreenResult() {
        val request = ScreenDecryptStore.begin()
        // This is sent from the separate instrumentation package, as another app would.
        val attacker = InstrumentationRegistry.getInstrumentation().context
        attacker.sendBroadcast(result(request, "forged plaintext")
            .setAction("com.wentuyi.app.SCREEN_DECRYPT_RESULT")
            .setPackage(context.packageName))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull(ScreenDecryptStore.consume(request))
    }

    @Test fun processingLeaseKeepsAConsumedResultPastTheOriginalRequestDeadline() {
        val request = ScreenDecryptStore.begin(200)
        assertTrue(ScreenDecryptStore.beginProcessing(request))
        android.os.SystemClock.sleep(300)
        assertTrue(ScreenDecryptStore.isActive(request))
        ScreenDecryptStore.save(result(request, "consumed message plaintext"))
        assertEquals("consumed message plaintext", ScreenDecryptStore.consume(request)
            ?.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT))
        assertNull(ScreenDecryptStore.consume(request))
    }

    @Test fun aProcessingLeaseCannotCompleteAfterAnExplicitNewRequest() {
        val old = ScreenDecryptStore.begin()
        assertTrue(ScreenDecryptStore.beginProcessing(old))
        val current = ScreenDecryptStore.begin()
        ScreenDecryptStore.save(result(old, "old message"))
        assertNull(ScreenDecryptStore.consume(current))
        assertFalse(ScreenDecryptStore.isActive(old))
        assertTrue(ScreenDecryptStore.isActive(current))
    }

    @Test fun aDuplicateCompletionCannotReplaceAnAwaitingPlaintextOrReacquireItsLease() {
        val request = ScreenDecryptStore.begin()
        assertTrue(ScreenDecryptStore.beginProcessing(request))
        ScreenDecryptStore.save(result(request, "original plaintext"))
        assertFalse(ScreenDecryptStore.beginProcessing(request))
        ScreenDecryptStore.save(Intent()
            .putExtra(ScreenDecryptActivity.EXTRA_REQUEST_ID, request)
            .putExtra(ScreenDecryptActivity.EXTRA_OK, false)
            .putExtra(ScreenDecryptActivity.EXTRA_MESSAGE, "duplicate start"))
        assertEquals("original plaintext", ScreenDecryptStore.consume(request)
            ?.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT))
    }

    private fun result(id: String, text: String) = Intent()
        .putExtra(ScreenDecryptActivity.EXTRA_REQUEST_ID, id)
        .putExtra(ScreenDecryptActivity.EXTRA_OK, true)
        .putExtra(ScreenDecryptActivity.EXTRA_KIND, ScreenDecryptActivity.KIND_TEXT)
        .putExtra(ScreenDecryptActivity.EXTRA_TEXT, text)
}
