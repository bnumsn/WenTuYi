package com.wentuyi.app

import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class KeyboardFeedbackTest {
    @Test fun hostWithoutCodePointApiDeletesBothEmojiSurrogates() = backspace("a😀", 2)

    @Test fun ordinaryBackspaceStillDeletesOneUtf16Unit() = backspace("ab", 1)

    @Test fun compactFeedbackKeepsTheForwardSecrecyWarningVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val service = TextImageImeService()
            val compact = TextImageImeService::class.java.getDeclaredMethod("compactStatus", String::class.java)
                .apply { isAccessible = true }
            assertEquals("暂无前向保密", compact.invoke(service,
                "已写入加密文字 (Alice)（本条暂无前向保密，待对方回复后自动启用）"))
            assertEquals("暂无前向保密", compact.invoke(service, "已分享加密二维码 (Alice)（暂无前向保密）"))
        }
    }

    private fun backspace(before: String, expectedUnits: Int) {
        var deletedUnits = 0
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> before.takeLast(args!![0] as Int)
                "deleteSurroundingTextInCodePoints" -> false // Also covers API 23's fallback.
                "deleteSurroundingText" -> { deletedUnits = args!![0] as Int; true }
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as InputConnection
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            TextImageImeService().deletePreviousCodePoint(connection)
        }
        assertEquals(expectedUnits, deletedUnits)
    }
}
