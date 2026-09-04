package com.wentuyi.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The dark scheme is easy to add and easy to get subtly wrong — one colour left at its
 * light value, or an accent that drops below readable contrast on a dark ground. This
 * checks both schemes mechanically rather than trusting a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class PaletteTest {

    private fun luminance(c: Int): Double {
        fun chan(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * chan(Color.red(c)) + 0.7152 * chan(Color.green(c)) + 0.0722 * chan(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun pairs(): List<Triple<String, Int, Int>> = listOf(
        Triple("正文/背景", Palette.textPrimary, Palette.surface),
        Triple("次要文字/背景", Palette.textSubtle, Palette.surface),
        Triple("强调色/背景", Palette.accentText, Palette.surface),
        Triple("危险色/背景", Palette.danger, Palette.surface),
        Triple("键盘文字/键帽", Palette.kbText, Palette.kbKey),
        Triple("键盘文字/功能键", Palette.kbText, Palette.kbFunctionKey),
        Triple("键盘次要/工具键", Palette.kbSubtle, Palette.kbToolbarKey),
        Triple("强调上的文字", Palette.kbOnAccent, Palette.kbAccent),
        Triple("警告文字/警告底", Palette.kbWarnText, Palette.kbWarnBg),
    )

    @Test fun both_schemes_meet_readable_contrast() {
        for (dark in listOf(false, true)) {
            applyScheme(dark)
            for ((name, fg, bg) in pairs()) {
                val ratio = contrast(fg, bg)
                assertTrue(
                    "${if (dark) "深色" else "浅色"} $name 对比度仅 %.2f:1（需 ≥ 4.5:1）".format(ratio),
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test fun keys_are_distinguishable_from_the_panel_behind_them() {
        // Letter keys were once painted the same colour as the panel, leaving no visible
        // key boundary at all. Require a real, if subtle, separation in both schemes.
        for (dark in listOf(false, true)) {
            applyScheme(dark)
            val ratio = contrast(Palette.kbKey, Palette.kbPanel)
            assertTrue("${if (dark) "深色" else "浅色"}：键帽与面板同色，看不出键位边界", ratio > 1.05)
            assertTrue("${if (dark) "深色" else "浅色"}：功能键与普通键难以区分",
                abs(luminance(Palette.kbFunctionKey) - luminance(Palette.kbKey)) > 0.005)
        }
    }

    @Test fun dark_scheme_actually_differs() {
        applyScheme(false)
        val lightSurface = Palette.surface
        val lightKbPanel = Palette.kbPanel
        applyScheme(true)
        assertTrue("深色背景没变", Palette.surface != lightSurface)
        assertTrue("深色键盘背景没变", Palette.kbPanel != lightKbPanel)
        assertTrue("深色背景应当是暗的", luminance(Palette.surface) < 0.1)
        assertTrue("深色键盘背景应当是暗的", luminance(Palette.kbPanel) < 0.1)
    }

    /** Drives Palette through its private light/dark switch via a synthetic configuration. */
    private fun applyScheme(dark: Boolean) {
        val ctx = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val config = android.content.res.Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                else android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        Palette.refresh(ctx.createConfigurationContext(config))
    }
}
