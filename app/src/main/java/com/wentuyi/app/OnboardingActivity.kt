package com.wentuyi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-launch tutorial: enables → selects the IME → generates an identity.
 *
 * Three sections; each refreshes its `[ ✓ ]` checkmark on resume by checking the
 * actual system state (enabled IME list, default IME setting, persisted identity)
 * rather than trusting an internal flag. Skip is allowed for power users.
 *
 * The "onboarding_done" preference lives in the dedicated `wentuyi_onboarding`
 * SharedPreferences so it can't collide with [WentuyiSettings] keystore data.
 */
class OnboardingActivity : Activity() {

    companion object {
        const val PREFS = "wentuyi_onboarding"
        const val KEY_DONE = "done"

        @JvmStatic
        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)

        @JvmStatic
        fun markDone(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply()
        }
    }

    private val scope: CoroutineScope = MainScope()

    private lateinit var step1Check: TextView
    private lateinit var step2Check: TextView
    private lateinit var step3Check: TextView
    private lateinit var step3Button: Button
    private lateinit var finishButton: Button
    // No-op slot left over from an earlier draft; kept to avoid renaming step3 row API.
    private var step3ActionLabel: (String) -> String = { it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshChecks()
    }

    override fun onResume() {
        super.onResume()
        refreshChecks()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(247, 248, 243))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        SystemBarPadding.apply(root, dp(22), dp(28), dp(22), dp(22))
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(heading("文图易首次设置"), matchWrap())
        root.addView(subtle("跟着这 3 步把键盘和身份码准备好，之后就能在任意聊天 App 里发加密内容。"),
            matchWrapWithTop(8))

        step1Check = stepRow(root,
            "①  启用文图易输入法",
            "在系统设置里勾选「文图易键盘」。Android 会弹出隐私警告，这是所有第三方输入法的标准提示。",
            "打开输入法设置"
        ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }

        step2Check = stepRow(root,
            "②  把文图易设为当前键盘",
            "在任意聊天框点击文本输入位置，下方会出现切换按钮；或者点下面的按钮直接弹出选择器。",
            "选择键盘"
        ) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showInputMethodPicker()
        }

        step3Check = stepRow(root,
            "③  生成你的身份码（X25519 公钥）",
            "用于安全加好友。生成后到「我的身份码 / 共享密钥」让对方扫码，双方核对 8 位校验码就能开始端到端加密通信。\n\n✓ 发给已验证联系人的加密文本和二维码会优先使用前向保密 (Double Ratchet)；共享密钥和棘轮首条消息暂无 PFS。",
            "现在生成"
        ) { ensureIdentityGenerated() }
        step3ActionLabel = { stepActionLabel ->
            // Surfaces "重置已损坏身份" instead of "现在生成" when there's a stored-but-
            // undecryptable identity pref (Keystore wiped after a system restore, etc).
            stepActionLabel
        }

        finishButton = Button(this).apply {
            text = "我已设置好"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener {
                markDone(this@OnboardingActivity)
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(finishButton, matchWrapWithTop(28))

        val skipBtn = Button(this).apply {
            text = "暂时跳过 (高级用户)"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                markDone(this@OnboardingActivity)
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(skipBtn, matchWrapWithTop(8))

        setContentView(scroll)
    }

    private fun stepRow(parent: LinearLayout, title: String, description: String,
                        actionLabel: String, action: () -> Unit): TextView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(21, 24, 18))
        }
        titleRow.addView(titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val check = TextView(this).apply {
            text = "未完成"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.rgb(150, 150, 150))
        }
        titleRow.addView(check, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(titleRow, matchWrap())

        container.addView(subtle(description).apply {
            setTextColor(Color.rgb(75, 80, 85))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, matchWrapWithTop(6))

        val actionBtn = Button(this).apply {
            text = actionLabel
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { action() }
        }
        container.addView(actionBtn, matchWrapWithTop(8))
        // Step 3 needs label switching ("现在生成" ↔ "重置已损坏身份")
        if (title.startsWith("③")) step3Button = actionBtn

        parent.addView(container, matchWrapWithTop(14))
        return check
    }

    // ─── State checks ──────────────────────────────────────────────────────

    private fun refreshChecks() {
        val pkg = packageName
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = imm?.enabledInputMethodList
            ?.any { it.packageName == pkg } == true
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isDefault = defaultIme?.startsWith("$pkg/") == true
        // Three states for the identity step:
        //   • readable identity ✓ — step done
        //   • corrupt pref (saved but undecryptable, e.g. Keystore wiped) → action
        //     button becomes "重置已损坏身份"
        //   • no pref → standard "现在生成"
        val readable = KeyExchange.isIdentityReadable(this)
        val corrupt = !readable && KeyExchange.isIdentityCorrupt(this)
        markCheck(step1Check, enabled)
        markCheck(step2Check, isDefault)
        markCheck(step3Check, readable)
        if (::step3Button.isInitialized) {
            step3Button.text = when {
                readable -> "✓ 已生成"
                corrupt -> "重置已损坏身份"
                else -> "现在生成"
            }
        }

        finishButton.isEnabled = enabled && isDefault && readable
        finishButton.text = if (finishButton.isEnabled) "✓ 我已设置好，进入主界面"
                            else "三步全部完成后可点击"
    }

    private fun markCheck(view: TextView, done: Boolean) {
        if (done) {
            view.text = "✓ 已完成"
            view.setTextColor(Color.rgb(32, 122, 89))
        } else {
            view.text = "未完成"
            view.setTextColor(Color.rgb(150, 150, 150))
        }
    }

    private fun ensureIdentityGenerated() {
        scope.launch {
            val freshlyGenerated = !KeyExchange.isIdentityReadable(this@OnboardingActivity)
            val wasCorrupt = KeyExchange.isIdentityCorrupt(this@OnboardingActivity)
            // If the existing pref is corrupted (decryption fails), drop it before
            // generating so the next getOrCreate writes cleanly. Without this, a
            // Keystore-wiped device would loop "现在生成" → load fails → user stuck.
            if (wasCorrupt) {
                KeyExchange.clearCorruptedIdentity(this@OnboardingActivity)
            }
            try {
                val identity = withContext(Dispatchers.Default) {
                    KeyExchange.getOrCreateIdentity(this@OnboardingActivity)
                }
                refreshChecks()
                finishButton.requestFocus()
                showStatus("身份码已生成：${identity.fingerprint}")
                if (freshlyGenerated) showBackupWarning(identity.fingerprint)
            } catch (e: Exception) {
                showStatus("生成失败：${e.message}")
            }
        }
    }

    /**
     * After a *fresh* identity is generated, drive home what this key is and how
     * losing it plays out — Gemini's evaluation flagged that the operational
     * Onboarding doesn't build a mental model, and unprotected users underestimate
     * the backup code's stakes.
     */
    private fun showBackupWarning(fingerprint: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠ 重要：你的身份只在这一台设备")
            .setMessage(
                "刚刚生成的私钥 (指纹 $fingerprint) 是你与所有联系人会话密钥的根。\n\n" +
                    "• 丢失 → 永久失联，所有联系人需重新扫码加你；\n" +
                    "• 泄漏 → 攻击者可冒充你发送消息、解密历史。\n\n" +
                    "请立即到「我的身份码 / 共享密钥 → 备份身份」抄写备份码，离线保管。\n\n" +
                    "已验证联系人的加密文本和二维码会优先使用前向保密 (Double Ratchet)；共享密钥和棘轮首条消息暂无 PFS，私钥泄漏仍可能解密这部分历史。"
            )
            .setPositiveButton("现在去备份") { _, _ ->
                startActivity(Intent(this, KeyManagementActivity::class.java))
            }
            .setNegativeButton("稍后处理", null)
            .setCancelable(false)
            .show()
    }

    private fun showStatus(message: String) {
        // Use the finish button area to surface progress.
        finishButton.text = message
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(21, 24, 18))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(95, 102, 90))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun matchWrapWithTop(topDp: Int): LinearLayout.LayoutParams =
        matchWrap().apply { topMargin = dp(topDp) }

    private fun dp(value: Int): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
    )
}
