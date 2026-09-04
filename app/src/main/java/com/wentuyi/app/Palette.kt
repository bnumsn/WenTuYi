package com.wentuyi.app

import android.content.Context
import android.content.res.Configuration

/**
 * The app's colours, in a light and a dark set.
 *
 * Everything used to be hard-coded light — `Theme.Material.Light`, `windowLightStatusBar`,
 * and ~53 literal `Color.rgb(...)` calls across the activities plus a wall of constants in
 * [KeyboardUi]. A blinding white keyboard rising over a dark chat app at night is the most
 * immediately felt flaw a keyboard can have, and this app is by definition used in dim,
 * private moments. Resolving from [Configuration.uiMode] needs no AndroidX, so it works at
 * minSdk 23 without touching the "no AppCompat" rule.
 *
 * [refresh] must run before any view is built and again on every configuration change; the
 * IME and each activity do so in onCreate / onConfigurationChanged.
 */
object Palette {

    @Volatile var isDark: Boolean = false
        private set

    // ─── App surfaces ─────────────────────────────────────────────────────────
    var surface = 0; private set          // window background
    var card = 0; private set             // raised panel on the window background
    var textPrimary = 0; private set
    var textSubtle = 0; private set
    var accent = 0; private set
    var accentText = 0; private set       // accent tuned for text on `surface`
    var onAccent = 0; private set         // text drawn on top of `accent`
    var warn = 0; private set
    var danger = 0; private set
    var ghost = 0; private set            // disabled / placeholder text

    // ─── Keyboard ─────────────────────────────────────────────────────────────
    var kbPanel = 0; private set
    var kbKey = 0; private set
    var kbKeyPressed = 0; private set
    var kbFunctionKey = 0; private set
    var kbFunctionPressed = 0; private set
    var kbToolbarKey = 0; private set
    var kbToolbarPressed = 0; private set
    var kbText = 0; private set
    var kbSubtle = 0; private set
    var kbAccent = 0; private set
    var kbAccentPressed = 0; private set
    var kbAccentTint = 0; private set
    var kbAccentTintPressed = 0; private set
    var kbDanger = 0; private set
    var kbStroke = 0; private set
    var kbOnAccent = 0; private set       // text drawn on top of kbAccent
    var kbWarnBg = 0; private set
    var kbWarnBgPressed = 0; private set
    var kbWarnText = 0; private set

    init { applyLight() }

    /** Re-resolves against [context]'s current night-mode state. Returns true if it flipped. */
    fun refresh(context: Context): Boolean {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night == isDark) return false
        isDark = night
        if (night) applyDark() else applyLight()
        return true
    }

    private fun applyLight() {
        surface = 0xFFF7F8F3.toInt()
        card = 0xFFFFFFFF.toInt()
        textPrimary = 0xFF151812.toInt()
        textSubtle = 0xFF5F665A.toInt()
        accent = 0xFF207A59.toInt()
        accentText = 0xFF1A6349.toInt()
        onAccent = 0xFFFFFFFF.toInt()
        warn = 0xFFB46400.toInt()
        danger = 0xFFC5221F.toInt()
        ghost = 0xFF969696.toInt()

        kbPanel = 0xFFF3F5F7.toInt()
        kbKey = 0xFFFFFFFF.toInt()
        kbKeyPressed = 0xFFE1E5EA.toInt()
        kbFunctionKey = 0xFFDCE2EA.toInt()
        kbFunctionPressed = 0xFFCBD3DD.toInt()
        kbToolbarKey = 0xFFFFFFFF.toInt()
        kbToolbarPressed = 0xFFE6EAEE.toInt()
        kbText = 0xFF1F2933.toInt()
        kbSubtle = 0xFF637083.toInt()
        kbAccent = 0xFF0F766E.toInt()
        kbAccentPressed = 0xFF0B5F59.toInt()
        kbAccentTint = 0xFFE0F2F1.toInt()
        kbAccentTintPressed = 0xFFB2DFDB.toInt()
        kbDanger = 0xFFC5221F.toInt()
        kbStroke = 0xFFD3DAE3.toInt()
        kbOnAccent = 0xFFFFFFFF.toInt()
        kbWarnBg = 0xFFFFF3E0.toInt()
        kbWarnBgPressed = 0xFFFFE0B2.toInt()
        kbWarnText = 0xFFA55916.toInt()   // darkened from B4641F: that was 4.00:1 on kbWarnBg
    }

    /**
     * Dark values are not the light ones inverted: greens and reds have to be lightened to
     * stay legible on a dark ground (#207A59 on near-black falls under 3:1), while surfaces
     * are kept near-neutral so the keyboard doesn't glow against a chat app's own dark theme.
     */
    private fun applyDark() {
        surface = 0xFF121410.toInt()
        card = 0xFF1D201A.toInt()
        textPrimary = 0xFFE8EBE3.toInt()
        textSubtle = 0xFFA2A99A.toInt()
        accent = 0xFF4FBF93.toInt()
        accentText = 0xFF6FD3AA.toInt()
        onAccent = 0xFF06231C.toInt()
        warn = 0xFFE0A050.toInt()
        danger = 0xFFFF7B75.toInt()
        ghost = 0xFF6E736A.toInt()

        kbPanel = 0xFF15171B.toInt()
        kbKey = 0xFF2A2E35.toInt()
        kbKeyPressed = 0xFF3B414A.toInt()
        kbFunctionKey = 0xFF1F2329.toInt()
        kbFunctionPressed = 0xFF2E333B.toInt()
        kbToolbarKey = 0xFF23272E.toInt()
        kbToolbarPressed = 0xFF333941.toInt()
        kbText = 0xFFE6EAF0.toInt()
        kbSubtle = 0xFF9AA5B4.toInt()
        kbAccent = 0xFF14B8A6.toInt()
        kbAccentPressed = 0xFF0D9488.toInt()
        kbAccentTint = 0xFF12403C.toInt()
        kbAccentTintPressed = 0xFF175E58.toInt()
        kbDanger = 0xFFFF7B75.toInt()
        kbStroke = 0xFF3A404A.toInt()
        kbOnAccent = 0xFF04211E.toInt()
        kbWarnBg = 0xFF3A2A12.toInt()
        kbWarnBgPressed = 0xFF4C3818.toInt()
        kbWarnText = 0xFFF0B96B.toInt()
    }
}
