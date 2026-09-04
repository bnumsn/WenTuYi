package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.app.Activity
import android.content.ClipboardManager
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

/**
 * Hub activity. The previous 999-line [MainActivity] has been split into:
 *   • this file (≈ 150 lines) — navigation + status,
 *   • [KeyManagementActivity] — identity, contacts, legacy passphrase,
 *   • [DecryptActivity] — incoming-intent + pick-image decryption,
 *   • [ScanActivity] — generic QR scanner that routes to the right next-step.
 *
 * Pre-existing share-intent filters (ACTION_SEND etc.) still target [MainActivity] in
 * the manifest for backwards compatibility — they're forwarded to [DecryptActivity]
 * on arrival.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var clipboardDecryptButton: Button
    private lateinit var clipboardEncryptButton: Button
    private lateinit var keyboardBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        // Sweep any decrypted-plaintext PNG whose short TTL has expired.
        ImageStore.pruneNow(this)
        // First-launch tutorial — runs once. Share intents (ACTION_SEND/MULTIPLE) still
        // go through the forwarder so receiving an encrypted image isn't blocked by setup.
        if (!OnboardingActivity.isDone(this) && !isSharedIntent(intent)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        buildUi()
        forwardIfDecryptIntent(intent)
    }

    private fun isSharedIntent(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT -> true
        else -> false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardIfDecryptIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshKeyboardBanner()
        refreshClipboardShortcut()
    }

    /** true when 文图易键盘 appears in the system's enabled-IME list. */
    private fun isKeyboardEnabled(): Boolean = runCatching {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@runCatching false
        imm.enabledInputMethodList.any { it.packageName == packageName }
    }.getOrDefault(false)

    /** true when it is also the keyboard currently in use. */
    private fun isKeyboardSelected(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
    }.getOrDefault(false)

    private fun refreshKeyboardBanner() {
        val enabled = isKeyboardEnabled()
        val selected = isKeyboardSelected()
        if (enabled && selected) {
            keyboardBanner.visibility = View.GONE
            return
        }
        keyboardBanner.visibility = View.VISIBLE
        val (message, action) = if (!enabled) {
            "⚠ 文图易键盘尚未启用 —— 在任何聊天 App 里都用不了加密按钮。点这里去开启。" to
                { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        } else {
            "文图易键盘已启用，但当前用的不是它。点这里切换。" to
                {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showInputMethodPicker()
                    Unit
                }
        }
        keyboardBanner.text = message
        keyboardBanner.setTextColor(if (!enabled) Palette.danger else Palette.warn)
        keyboardBanner.background = KeyboardUi.roundedSelector(
            this, Palette.card, Palette.surface, 10,
            if (!enabled) Palette.danger else Palette.warn, 1)
        keyboardBanner.setOnClickListener { action() }
    }

    private fun forwardIfDecryptIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val forward = Intent(this, DecryptActivity::class.java).apply {
                    action = intent.action
                    intent.type?.let { type = it }
                    intent.extras?.let { putExtras(it) }
                    intent.clipData?.let { clipData = it }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(forward)
                finish()
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    openDecryptText(text)
                    finish()
                }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.surface)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        SystemBarPadding.apply(root, dp(22), dp(18), dp(22), dp(22))
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(textView("文图易", 30f, true), matchWrap())

        // Keyboard state, first thing on the page. Onboarding checks this properly but the
        // hub never did, so a user who later switched keyboards away — or who skipped a
        // setup step — saw nothing wrong and simply concluded the app didn't work. The
        // banner is only shown when something is actually off, and it fixes it in one tap.
        keyboardBanner = textView("", 14f, false).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(keyboardBanner, matchWrapWithTop(12))

        statusView = textView("", 14f, false).apply { setTextColor(Palette.textSubtle) }
        root.addView(statusView, matchWrapWithTop(8))

        // Clipboard is the one route that needs nothing from the host app at all — no
        // keyboard switch, no <queries> declaration, no share sheet. Encrypt was missing
        // its half of this pair, which meant the only way to encrypt was the IME.
        clipboardEncryptButton = Button(this).apply {
            text = "加密剪贴板里的文字"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
            setOnClickListener {
                clipboardPlainText()?.let { startActivity(EncryptActivity.intentFor(this@MainActivity, it)) }
            }
        }
        root.addView(clipboardEncryptButton, matchWrapWithTop(12))

        clipboardDecryptButton = Button(this).apply {
            text = "解密剪贴板里的文图易密文"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
            setOnClickListener {
                clipboardWentuyiPayload()?.let { openDecryptText(it) }
            }
        }
        root.addView(clipboardDecryptButton, matchWrapWithTop(12))

        // The permanent encrypt door. Until now encrypting was reachable only from the IME
        // (i.e. only after replacing your keyboard) or from a clipboard shortcut that
        // appeared only when the clipboard happened to hold plain text.
        val encryptButton = Button(this).apply {
            text = "加密一段文字"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Palette.onAccent)
            background = KeyboardUi.roundedSelector(
                this@MainActivity, Palette.accent, Palette.accentText, 10, Color.TRANSPARENT, 0)
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { startActivity(EncryptActivity.intentFor(this@MainActivity, "")) }
        }
        root.addView(encryptButton, matchWrapWithTop(16))

        addPrimaryButton(root, "我的身份码 / 共享密钥") {
            startActivity(Intent(this, KeyManagementActivity::class.java))
        }
        addPrimaryButton(root, "扫码 / 导入二维码") {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        addPrimaryButton(root, "解密接收的内容") {
            startActivity(Intent(this, DecryptActivity::class.java))
        }

        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(sysRow, matchWrapWithTop(18))
        sysRow.addView(secondaryButton("输入法设置") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, weightWrap(1))
        sysRow.addView(secondaryButton("选择键盘") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showInputMethodPicker()
        }, weightWrapWithLeft(1, 10))

        if (BuildConfig.DEBUG) {
            addSecondaryButton(root, "键盘本地测试") {
                startActivity(Intent(this, KeyboardTestActivity::class.java))
            }
        }
        setContentView(scroll)
    }

    private fun refreshStatus() {
        statusView.text = try {
            val identity = KeyExchange.loadIdentity(this)
            val hasPassphrase = WentuyiSettings.hasSavedPassphrase(this)
            val pieces = ArrayList<String>()
            // The fingerprint alone told the user nothing; label what it is for.
            if (identity != null) pieces += "身份码已生成（指纹 ${identity.fingerprint}）"
            if (hasPassphrase) pieces += "共享密钥已设置"
            if (pieces.isEmpty() && WentuyiSettings.isUsingDefaultPassphrase(this))
                pieces += "当前使用开发默认密钥"
            if (pieces.isEmpty()) pieces += "尚未配置密钥，请先打开「我的身份码」"
            pieces.joinToString("  ·  ")
        } catch (e: RuntimeException) {
            e.message ?: "密钥状态未知"
        }
    }

    private fun refreshClipboardShortcut() {
        val payload = clipboardWentuyiPayload()
        clipboardDecryptButton.visibility = if (payload == null) View.GONE else View.VISIBLE
        // Only offer to encrypt when the clipboard holds something that isn't already ours.
        clipboardEncryptButton.visibility =
            if (payload == null && clipboardPlainText() != null) View.VISIBLE else View.GONE
    }

    /** Clipboard text that is *not* a Wentuyi payload — i.e. something worth encrypting. */
    private fun clipboardPlainText(): String? = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching null
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            ?: return@runCatching null
        text.takeIf {
            it.isNotEmpty() && it.length <= 20_000 &&
                !SecurePayloadCodec.isPayload(it) && !it.startsWith(DoubleRatchet.PREFIX_V5)
        }
    }.getOrNull()

    private fun clipboardWentuyiPayload(): String? = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching null
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (clip.itemCount == 0) return@runCatching null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            ?: return@runCatching null
        text.takeIf { SecurePayloadCodec.isPayload(it) || it.startsWith(DoubleRatchet.PREFIX_V5) }
    }.getOrNull()

    private fun openDecryptText(payload: String) {
        startActivity(Intent(this, DecryptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        })
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun addPrimaryButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
        }
        parent.addView(button, matchWrapWithTop(12))
    }

    private fun addSecondaryButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(secondaryButton(label, onClick), matchWrapWithTop(10))
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { onClick() }
        }

    private fun textView(text: String, sizeSp: Float, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun matchWrapWithTop(topDp: Int): LinearLayout.LayoutParams =
        matchWrap().apply { topMargin = dp(topDp) }

    private fun weightWrap(weight: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat())

    private fun weightWrapWithLeft(weight: Int, leftDp: Int): LinearLayout.LayoutParams =
        weightWrap(weight).apply { leftMargin = dp(leftDp) }

    private fun dp(value: Int): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
    )
}
