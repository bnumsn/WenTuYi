package com.wentuyi.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImageDeliveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun rejectedInlineImageKeepsPlaintextClearedWhenShareLaunches() = deliver(shareSucceeds = true) { ime ->
        assertTrue("host rejected the inline image", ime.inlineAttempts > 0)
        assertTrue("share was launched", ime.shareAttempts > 0)
        assertEquals("share launch must see an empty source field", "", ime.textAtShare)
        assertEquals("successful fallback must not restore sendable plaintext", "", ime.fieldText)
    }

    @Test fun allDeliveryFailuresRestoreTheOriginalFieldOnlyAfterSharingFails() = deliver(shareSucceeds = false) { ime ->
        assertTrue(ime.inlineAttempts > 0)
        assertTrue(ime.shareAttempts > 0)
        assertEquals("", ime.textAtShare)
        assertEquals(SOURCE, ime.fieldText)
    }

    @Test fun hostEditDuringRejectedContentIsPreservedWhenSharingAlsoFails() =
        deliver(shareSucceeds = false, hostEdit = "host replacement") { ime ->
            assertEquals("host replacement", ime.fieldText)
        }

    @Test fun completeDeliveryFailureRestoresASelectionWithoutRemovingSurroundingText() =
        deliver(shareSucceeds = false, selectionInsideField = true) { ime ->
            assertEquals("prefix " + SOURCE + " suffix", ime.fieldText)
            assertEquals("prefix  suffix", ime.textAtShare)
        }

    private fun deliver(shareSucceeds: Boolean, hostEdit: String? = null, selectionInsideField: Boolean = false,
        assertions: (FakeIme) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= 25)
        val complete = CountDownLatch(1)
        lateinit var ime: FakeIme
        lateinit var scope: CoroutineScope
        instrumentation.runOnMainSync {
            ime = FakeIme(context, shareSucceeds, hostEdit, selectionInsideField)
            scope = MainScope()
            val controller = SendController(ime, scope, { status ->
                if (status.startsWith("已分享") || status == "没有可用的分享应用" || status.contains("失败")) complete.countDown()
            }, { SendTarget.SharedPassphrase }, { 1L })
            controller.generateEncryptedImage(SOURCE)
        }
        try {
            assertTrue("send completed", complete.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertions(ime) }
        } finally { instrumentation.runOnMainSync { scope.cancel() } }
    }

    private class FakeIme(context: Context, private val shareSucceeds: Boolean, private val hostEdit: String?,
        selectionInsideField: Boolean) : InputMethodService() {
        var fieldText = if (selectionInsideField) "prefix " + SOURCE + " suffix" else SOURCE
        private var selectedText: String? = if (selectionInsideField) SOURCE else null
        private var cursor = if (selectionInsideField) "prefix ".length else fieldText.length
        var inlineAttempts = 0
        var shareAttempts = 0
        var textAtShare: String? = null
        private val editor = EditorInfo().apply {
            packageName = context.packageName
            fieldId = 12
            contentMimeTypes = arrayOf("image/png")
        }
        private val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getSelectedText" -> selectedText
                "getTextBeforeCursor" -> fieldText.substring(0, cursor)
                "getTextAfterCursor" -> fieldText.substring(cursor + (selectedText?.length ?: 0))
                "deleteSurroundingText" -> {
                    val before = args!![0] as Int
                    val after = args[1] as Int
                    fieldText = fieldText.substring(0, cursor - before) + fieldText.substring(cursor + after)
                    cursor -= before
                    true
                }
                "commitText" -> {
                    val text = args!![0].toString()
                    fieldText = fieldText.substring(0, cursor) + text + fieldText.substring(cursor + (selectedText?.length ?: 0))
                    selectedText = null
                    cursor += text.length
                    true
                }
                "commitContent" -> {
                    inlineAttempts++
                    hostEdit?.let { fieldText = it; selectedText = null; cursor = it.length }
                    false
                }
                "beginBatchEdit", "endBatchEdit" -> true
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as InputConnection

        init { attachBaseContext(context) }
        override fun getCurrentInputConnection(): InputConnection = connection
        override fun getCurrentInputEditorInfo(): EditorInfo = editor
        override fun startActivity(intent: Intent) {
            shareAttempts++
            textAtShare = fieldText
            if (!shareSucceeds) throw ActivityNotFoundException("simulated share failure")
        }
    }

    private companion object { const val SOURCE = "分享二维码后聊天框不能保留原文" }
}
