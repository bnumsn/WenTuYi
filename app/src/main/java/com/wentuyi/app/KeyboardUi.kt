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

/** Visual constants + button-builders shared by the IME input view. */
object KeyboardUi {
    const val COLOR_PANEL = 0xFFF3F5F7.toInt()
    const val COLOR_KEY = 0xFFFFFFFF.toInt()
    const val COLOR_KEY_PRESSED = 0xFFE1E5EA.toInt()
    const val COLOR_FUNCTION_KEY = 0xFFDCE2EA.toInt()
    const val COLOR_FUNCTION_PRESSED = 0xFFCBD3DD.toInt()
    const val COLOR_TOOLBAR_KEY = 0xFFFFFFFF.toInt()
    const val COLOR_TOOLBAR_PRESSED = 0xFFE6EAEE.toInt()
    const val COLOR_TEXT = 0xFF1F2933.toInt()
    const val COLOR_SUBTLE = 0xFF637083.toInt()
    const val COLOR_ACCENT = 0xFF0F766E.toInt()
    const val COLOR_ACCENT_PRESSED = 0xFF0B5F59.toInt()
    const val COLOR_ACCENT_TINT = 0xFFE0F2F1.toInt()
    const val COLOR_ACCENT_TINT_PRESSED = 0xFFB2DFDB.toInt()
    const val COLOR_DANGER = 0xFFC5221F.toInt()
    const val COLOR_STROKE = 0xFFD3DAE3.toInt()
    const val KEY_HEIGHT_DP = 48
    const val TOOLBAR_HEIGHT_DP = 32

    fun keyboardButton(ctx: Context, label: String, functionKey: Boolean): Button {
        val button = Button(ctx)
        stripChrome(button)
        button.text = label
        button.setTextColor(COLOR_TEXT)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, keyTextSize(label).toFloat())
        val normal = if (functionKey || label == "空格" || label == "space") COLOR_FUNCTION_KEY else COLOR_PANEL
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
            ctx, 0xFFFAFAFA.toInt(), COLOR_TOOLBAR_PRESSED, 16, COLOR_STROKE, 1
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
        button.setTextColor(Color.WHITE)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 8, Color.TRANSPARENT, 0)
    }

    fun styleActiveToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(Color.WHITE)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 16, Color.TRANSPARENT, 0)
    }

    fun styleDefaultToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(COLOR_TEXT)
        button.background = roundedSelector(ctx, COLOR_TOOLBAR_KEY, COLOR_TOOLBAR_PRESSED, 16, Color.TRANSPARENT, 0)
    }

    /** Tinted style for the secure-send buttons (密文 / 密图) so they group visually. */
    fun styleSecureToolbarKey(ctx: Context, button: Button) {
        button.setTextColor(Color.WHITE)
        button.background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 14, Color.TRANSPARENT, 0)
    }

    fun keyParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, KEY_HEIGHT_DP), weight).apply {
            leftMargin = dp(ctx, leftDp)
        }

    fun toolbarParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, TOOLBAR_HEIGHT_DP), weight).apply {
            leftMargin = dp(ctx, leftDp)
        }

    fun candidateParams(ctx: Context, leftDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(ctx, 42), weight).apply { leftMargin = dp(ctx, leftDp) }

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
                setTextColor(Color.WHITE)
                background = roundedSelector(ctx, COLOR_ACCENT, COLOR_ACCENT_PRESSED, 14, Color.TRANSPARENT, 0)
            } else {
                setTextColor(0xFFB4641F.toInt())
                background = roundedSelector(ctx, 0xFFFFF3E0.toInt(), 0xFFFFE0B2.toInt(), 14, 0xFFB4641F.toInt(), 1)
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
