package com.wentuyi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Encrypts text that arrived from outside the keyboard — the share sheet, a text-selection
 * menu, or the clipboard. The counterpart to [DecryptActivity].
 *
 * **Why this exists.** Encrypting used to be reachable only from the IME, which meant the
 * app's core action was gated behind "replace your keyboard" — a system flow that ends with
 * Android warning the user that this app can read everything they type. This screen gives
 * the same capability to people who keep their own keyboard.
 *
 * **Three entry points, deliberately ranked by how reliable they are:**
 *  - `ACTION_SEND` (share sheet) — resolved by the system chooser, so it is visible from
 *    every app regardless of package-visibility rules. This is the dependable one.
 *  - Clipboard, from the hub — always available, needs no host-app cooperation at all.
 *  - `ACTION_PROCESS_TEXT` (text-selection menu) — by far the nicest when it works, because
 *    [finishWithReplacement] swaps the ciphertext straight back into the field the user
 *    selected. But since Android 11 a host app only sees third-party PROCESS_TEXT handlers
 *    if it declared `<queries>` for them, and most apps (Chrome, Gmail, Messages, Contacts
 *    and Docs among them) do not. Treated as a bonus, never as the main path.
 */
class EncryptActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "com.wentuyi.app.extra.PLAINTEXT"

        /** Launches the encrypt screen for [text] (used by the hub's clipboard shortcut). */
        fun intentFor(context: Context, text: String): Intent =
            Intent(context, EncryptActivity::class.java).putExtra(EXTRA_TEXT, text)
    }

    private val scope: CoroutineScope = MainScope()
    private lateinit var statusView: TextView
    private lateinit var sourceView: EditText
    private lateinit var targetButton: Button
    private lateinit var resultView: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var qrContainer: LinearLayout

    private var sourceText: String = ""
    private var payload: String? = null
    private var targets: List<SendTarget> = emptyList()
    private var targetIndex = 0
    /** True when we were opened from a text-selection menu that accepts a replacement. */
    private var canReplaceInPlace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        sourceText = extractText(intent)
        canReplaceInPlace = intent?.action == Intent.ACTION_PROCESS_TEXT &&
            intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false).not()
        buildUi()
        refreshTargets()
        // Only complain about missing text when text was actually expected. Opening a blank
        // screen from the hub and being greeted by an error is just noise.
        val expectedText = intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_PROCESS_TEXT
        if (sourceText.isEmpty() && expectedText) {
            statusView.text = "没有收到可加密的文字"
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun extractText(intent: Intent?): String {
        if (intent == null) return ""
        val raw = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent.getStringExtra(EXTRA_TEXT)
        } ?: intent.getStringExtra(EXTRA_TEXT)
        return raw?.trim().orEmpty()
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.surface)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        SystemBarPadding.apply(root, dp(22), dp(18), dp(22), dp(22))
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "加密发送"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Palette.textPrimary)
        }, matchWrap())

        statusView = subtle("")
        root.addView(statusView, matchWrapWithTop(6))

        root.addView(subtle("要加密的文字"), matchWrapWithTop(16))
        // Editable, not a read-only preview: text arriving from a share sheet usually needs
        // trimming, and the hub's "写一段文字加密" entry starts here with nothing at all.
        sourceView = EditText(this).apply {
            setText(sourceText)
            hint = "在这里输入或粘贴要加密的文字"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Palette.textPrimary)
            setHintTextColor(Palette.ghost)
            setBackgroundColor(Palette.card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        root.addView(sourceView, matchWrapWithTop(6))

        root.addView(subtle("加密给"), matchWrapWithTop(16))
        targetButton = primaryButton("…") { showTargetPicker() }
        root.addView(targetButton, matchWrapWithTop(6))

        root.addView(accentButton("加密") { encrypt() }, matchWrapWithTop(16))

        resultView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Palette.textSubtle)
            setBackgroundColor(Palette.card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        root.addView(resultView, matchWrapWithTop(14))

        actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(actionsRow, matchWrapWithTop(10))

        qrContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(qrContainer, matchWrapWithTop(10))

        setContentView(scroll)
    }

    private fun refreshTargets() {
        targets = MessageEncryptor.availableTargets(this)
        if (targets.isEmpty()) {
            targetButton.text = "尚未设置密钥"
            targetButton.isEnabled = false
            statusView.text = "请先到主 App 生成身份码，或保存一个共享密钥"
            return
        }
        targetIndex = targetIndex.coerceIn(0, targets.size - 1)
        targetButton.text = MessageEncryptor.label(targets[targetIndex])
    }

    private fun showTargetPicker() {
        if (targets.isEmpty()) return
        val labels = targets.map { MessageEncryptor.label(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("加密给谁")
            .setSingleChoiceItems(labels, targetIndex) { dialog, which ->
                targetIndex = which
                targetButton.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── Encrypt ──────────────────────────────────────────────────────────────

    private fun encrypt() {
        sourceText = sourceView.text.toString().trim()
        if (sourceText.isEmpty()) { statusView.text = "没有可加密的文字"; return }
        val target = targets.getOrNull(targetIndex) ?: run {
            statusView.text = "请先选择加密目标"; return
        }
        statusView.text = "正在加密…"
        scope.launch {
            try {
                val enc = withContext(Dispatchers.Default) {
                    MessageEncryptor.encryptText(this@EncryptActivity, target, sourceText)
                }
                payload = enc.payload
                // PROCESS_TEXT can hand the ciphertext straight back into the field the user
                // selected — no clipboard round trip, no leaving the conversation.
                if (canReplaceInPlace) { finishWithReplacement(enc.payload); return@launch }
                showResult(enc)
            } catch (e: Exception) {
                statusView.text = "加密失败：${e.message ?: e::class.java.simpleName}"
            }
        }
    }

    private fun finishWithReplacement(ciphertext: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, ciphertext))
        finish()
    }

    private fun showResult(enc: MessageEncryptor.EncryptedText) {
        // Copy immediately: every non-PROCESS_TEXT route ends in the user pasting this back
        // into a chat, so making them tap 复制 first is a step with no purpose.
        copyToClipboard(enc.payload)
        statusView.text = buildString {
            append("已加密并复制到剪贴板，粘贴到聊天框即可发送。")
            if (enc.noForwardSecrecy) {
                append("\n⚠ 本条暂无前向保密：对方还没回过消息，棘轮尚未建立。")
            }
        }
        statusView.setTextColor(if (enc.noForwardSecrecy) Palette.warn else Palette.textSubtle)
        resultView.text = enc.payload
        resultView.visibility = View.VISIBLE
        buildResultActions(enc.payload)
    }

    private fun buildResultActions(payload: String) {
        actionsRow.removeAllViews()
        actionsRow.visibility = View.VISIBLE
        actionsRow.addView(smallButton("再次复制") { copyToClipboard(payload) }, weight(1f))
        actionsRow.addView(smallButton("分享文本") { shareText(payload) }, weightWithLeft(1f, 10))
        actionsRow.addView(smallButton("生成二维码") { renderQr(payload) }, weightWithLeft(1f, 10))
    }

    private fun renderQr(payload: String) {
        statusView.text = "正在生成二维码…"
        scope.launch {
            try {
                val bitmaps = withContext(Dispatchers.Default) {
                    TextImageCodec.renderEncryptedPayloadAsQr(payload)
                }
                qrContainer.removeAllViews()
                val uris = withContext(Dispatchers.IO) {
                    bitmaps.map { ImageStore.savePng(this@EncryptActivity, it) }
                }
                for (bitmap in bitmaps) {
                    qrContainer.addView(ImageView(this@EncryptActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                    }, matchWrapWithTop(10))
                }
                qrContainer.addView(
                    primaryButton(if (uris.size > 1) "分享 ${uris.size} 张二维码" else "分享二维码") {
                        if (uris.size > 1) IntentHelpers.shareImages(this@EncryptActivity, uris, "分享加密二维码")
                        else IntentHelpers.shareImage(this@EncryptActivity, uris[0], "分享加密二维码")
                    },
                    matchWrapWithTop(10),
                )
                statusView.text = if (bitmaps.size > 1)
                    "内容较长，已拆成 ${bitmaps.size} 张二维码，需全部发给对方"
                else "二维码已生成"
            } catch (e: Exception) {
                statusView.text = "生成二维码失败：${e.message}"
            }
        }
    }

    private fun shareText(payload: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        runCatching { startActivity(Intent.createChooser(share, "分享加密文本")) }
            .onFailure { statusView.text = "没有可分享的应用" }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("文图易密文", text))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Palette.textSubtle)
    }

    /** The one action this screen exists for; styled so it doesn't look like the others. */
    private fun accentButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(Palette.onAccent)
        background = KeyboardUi.roundedSelector(
            this@EncryptActivity, Palette.accent, Palette.accentText, 10, android.graphics.Color.TRANSPARENT, 0)
        setPadding(0, dp(14), 0, dp(14))
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun primaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setOnClickListener { action() }
    }

    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setOnClickListener { action() }
    }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun matchWrapWithTop(topDp: Int): LinearLayout.LayoutParams =
        matchWrap().apply { topMargin = dp(topDp) }

    private fun weight(w: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, w)

    private fun weightWithLeft(w: Float, leftDp: Int): LinearLayout.LayoutParams =
        weight(w).apply { leftMargin = dp(leftDp) }

    private fun dp(value: Int): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics))
}
