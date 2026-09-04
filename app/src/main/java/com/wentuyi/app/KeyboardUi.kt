package com.wentuyi.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Visual constants + button-builders shared by the IME input view.
 *
 * Colours delegate to [Palette] so the keyboard follows the system light/dark setting;
 * they stay `KeyboardUi.COLOR_*` so every call site reads the same as before.
 */
object KeyboardUi {
    val COLOR_PANEL get() = Palette.kbPanel
    val COLOR_KEY get() = Palette.kbKey
    val COLOR_KEY_PRESSED get() = Palette.kbKeyPressed
    val COLOR_FUNCTION_KEY get() = Palette.kbFunctionKey
    val COLOR_FUNCTION_PRESSED get() = Palette.kbFunctionPressed
    val COLOR_TOOLBAR_KEY get() = Palette.kbToolbarKey
    val COLOR_TOOLBAR_PRESSED get() = Palette.kbToolbarPressed
    val COLOR_TEXT get() = Palette.kbText
    val COLOR_SUBTLE get() = Palette.kbSubtle
    val COLOR_ACCENT get() = Palette.kbAccent
    val COLOR_ACCENT_PRESSED get() = Palette.kbAccentPressed
    val COLOR_ACCENT_TINT get() = Palette.kbAccentTint
    val COLOR_ACCENT_TINT_PRESSED get() = Palette.kbAccentTintPressed
    val COLOR_DANGER get() = Palette.kbDanger
    val COLOR_STROKE get() = Palette.kbStroke
    val COLOR_ON_ACCENT get() = Palette.kbOnAccent

    const val KEY_HEIGHT_DP = 48
    /**
     * 48dp is Android's minimum touch target. The tool strip and the decrypt panel's
     * 写入/复制/关闭 used to be 32dp — and the decrypt panel is exactly the moment the user
     * most needs to hit the right button. Landscape shrinks these; see [compactRows].
     */
    const val TOOLBAR_HEIGHT_DP = 48
    const val CANDIDATE_HEIGHT_DP = 48

    /**
     * Landscape has roughly half the vertical room, and a keyboard sized for portrait
     * covers the conversation it is being used to reply to. Every row height is scaled by
     * this instead of being duplicated in a values-land resource, because the whole input
     * view is built in code.
     */
    @Volatile private var compactRows = false

    fun setCompactRows(compact: Boolean) { compactRows = compact }

    private fun rowHeight(dp: Int): Int = if (compactRows) maxOf(36, (dp * 0.72f).toInt()) else dp

    fun keyboardButton(ctx: Context, label: String, functionKey: Boolean): Button {
        val button = Button(ctx)
        stripChrome(button)
        button.text = label
        button.setTextColor(COLOR_TEXT)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, keyTextSize(label).toFloat())
        // Letter keys use COLOR_KEY, not COLOR_PANEL. They used to be painted the same
        // colour as the panel behind them, which left the keys with no visible boundary at
        // all — only floating glyphs. COLOR_KEY existed but nothing referenced it.
        val normal = if (functionKey || label == "空格" || label == "space") COLOR_FUNCTION_KEY else COLOR_KEY
        val pressed = if (functionKey) COLOR_FUNCTION_PRESSED else COLOR_KEY_PRESSED
        button.background = roundedSelector(ctx, normal, pressed, 8, Color.TRANSPARENT, 0)
        button.elevation = 0f
        return button
    }

    fun toolbarButton(ctx: Context, label: String): Button {
        val button = Button(ctx)
        stripChrome(button)
        button.text = label
        button.setTextColor(COLOR_TEXT)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 11f else 12f)
        button.background = roundedSelector(ctx, COLOR_TOOLBAR_KEY, COLOR_TOOLBAR_PRESSED, 16, Color.TRANSPARENT, 0)
        return button
    }

    fun candidateButton(ctx: Context, label: String): Button {
        val button = Button(ctx)
        stripChrome(button)
        button.text = label
        button.setTextColor(COLOR_TEXT)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 3) 12f else 15f)
        button.background = roundedSelector(ctx, COLOR_TOOLBAR_KEY, COLOR_TOOLBAR_PRESSED, 16, Color.TRANSPARENT, 0)
        return button
    }

    /** Ghost-styled button for the raw-pinyin "commit as typed" entry. */
    fun rawPinyinButton(ctx: Context, label: String): Button {
        val button = Button(ctx)
        stripChrome(button)
        button.text = label
        button.setTextColor(COLOR_SUBTLE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        button.background = roundedSelector(
            ctx, COLOR_TOOLBAR_KEY, COLOR_TOOLBAR_PRESSED, 16, COLOR_STROKE, 1
        )
        return button
    }

    /** Highlights the top pinyin candidate so the user knows which one space/enter commits. */
    fun styleFirstCandidate(ctx: Context, button: Button) {
        button.setTextColor(COLOR_ACCENT)
        button.setTypeface(button.typeface, android.graphics.Typeface.BOLD)
        button.background = roundedSelector(
            ctx, COLOR_ACCENT_TINT, COLOR_ACCENT_TINT_PRESSED, 16, COLOR_ACCENT, 1
        )
    }

    fun styleActiveKey(ctx: Context, button: Button) {
        button.setTextColor(COLOR_ON_ACCENT)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 8, Color.TRANSPARENT, 0)
    }

    fun styleActiveToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(COLOR_ON_ACCENT)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 16, Color.TRANSPARENT, 0)
    }

    fun styleDefaultToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(COLOR_TEXT)
        button.background = roundedSelector(ctx, COLOR_TOOLBAR_KEY, COLOR_TOOLBAR_PRESSED, 16, Color.TRANSPARENT, 0)
    }

    /** Tinted style for the secure-send buttons (密文 / 密图) so they group visually. */
    fun styleSecureToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(COLOR_ON_ACCENT)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 14, Color.TRANSPARENT, 0)
    }

    fun keyParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, rowHeight(KEY_HEIGHT_DP)), weight).apply {
            leftMargin = dp(ctx, leftDp)
        }

    fun toolbarParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, rowHeight(TOOLBAR_HEIGHT_DP)), weight).apply {
            leftMargin = dp(ctx, leftDp)
        }

    fun candidateParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, rowHeight(CANDIDATE_HEIGHT_DP)), weight)
            .apply { leftMargin = dp(ctx, leftDp) }

    fun candidateStripHeight(ctx: Context): Int = dp(ctx, rowHeight(CANDIDATE_HEIGHT_DP))

    /**
     * A full-width, tappable mode banner shown in the candidate strip when there's
     * no pinyin in progress. Makes the current input mode unmistakable — users were
     * typing in English mode without realizing it and concluding "can't type Chinese".
     * Chinese = green/accent; English = amber warning so it stands out as "not the
     * mode you probably want for Chinese".
     */
    fun modeBanner(ctx: Context, text: String, chineseMode: Boolean): TextView {
        return TextView(ctx).apply {
            this.text = text
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            if (chineseMode) {
                setTextColor(COLOR_ON_ACCENT)
                background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 14, Color.TRANSPARENT, 0)
            } else {
                setTextColor(Palette.kbWarnText)
                background = roundedSelector(
                    ctx, Palette.kbWarnBg, Palette.kbWarnBgPressed, 14, Palette.kbWarnText, 1)
            }
        }
    }

    fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun matchWrapWithTop(ctx: Context, topDp: Int): LinearLayout.LayoutParams =
        matchWrap().apply { topMargin = dp(ctx, topDp) }

    fun matchFixedHeight(ctx: Context, heightDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp))

    fun matchFixedHeightWithTop(ctx: Context, heightDp: Int, topDp: Int): LinearLayout.LayoutParams =
        matchFixedHeight(ctx, heightDp).apply { topMargin = dp(ctx, topDp) }

    fun dp(ctx: Context, value: Int): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics)
    )

    private fun stripChrome(button: Button) {
        button.isAllCaps = false
        button.transformationMethod = null
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.isSingleLine = true
        button.maxLines = 1
        button.minHeight = 0
        button.minimumHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.isFocusable = false
        button.isFocusableInTouchMode = false
        button.setPadding(0, 0, 0, 0)
        button.stateListAnimator = null
    }

    private fun keyTextSize(label: String): Int = when (label) {
        "⌫", "⇧", "↵" -> 22
        "123", "ABC", "中文", "English" -> 12
        "中", "En" -> 14
        else -> if (label.length > 2) 12 else 18
    }

    fun roundedSelector(
        ctx: Context,
        normalColor: Int, pressedColor: Int,
        cornerRadiusDp: Int, strokeColor: Int, strokeWidthDp: Int,
    ): StateListDrawable {
        val selector = StateListDrawable()
        selector.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedDrawable(ctx, pressedColor, cornerRadiusDp, strokeColor, strokeWidthDp)
        )
        selector.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedDrawable(ctx, pressedColor, cornerRadiusDp, strokeColor, strokeWidthDp)
        )
        selector.addState(
            intArrayOf(),
            roundedDrawable(ctx, normalColor, cornerRadiusDp, strokeColor, strokeWidthDp)
        )
        return selector
    }

    private fun roundedDrawable(
        ctx: Context, color: Int, cornerRadiusDp: Int, strokeColor: Int, strokeWidthDp: Int
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(ctx, cornerRadiusDp).toFloat()
        if (strokeWidthDp > 0) setStroke(dp(ctx, strokeWidthDp), strokeColor)
    }
}
