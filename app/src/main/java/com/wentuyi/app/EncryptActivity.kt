package com.wentuyi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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

    private lateinit var operation: EncryptOperation
    private var suppressDraftEdits = false
    private var payload: String? = null
    private var targets: List<SendTarget> = emptyList()
    private val selection get() = operation.selection
    private val busy get() = operation.busy
    private var resultGeneration = 0L
    private lateinit var encryptButton: Button
    /** True when we were opened from a text-selection menu that accepts a replacement. */
    private var canReplaceInPlace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Palette.refresh(this)
        val retained = lastNonConfigurationInstance as? EncryptOperation
        operation = retained ?: EncryptOperation(applicationContext,
            if (savedInstanceState == null) extractText(intent) else "")
        canReplaceInPlace = intent?.action == Intent.ACTION_PROCESS_TEXT &&
            intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false).not()
        buildUi()
        refreshTargets()
        operation.attach(::renderOperationState)
        // Only complain about missing text when text was actually expected. Opening a blank
        // screen from the hub and being greeted by an error is just noise.
        val expectedText = intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_PROCESS_TEXT
        if (retained == null && savedInstanceState == null && operation.draft.isEmpty() && expectedText) {
            statusView.text = "没有收到可加密的文字"
        } else if (retained == null && savedInstanceState != null) {
            statusView.text = "页面已恢复；草稿仅保存在内存中，请重新输入并选择加密目标"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
    }

    override fun onRetainNonConfigurationInstance(): Any = operation

    override fun onDestroy() {
        operation.detach()
        // Clear the abandoned view without treating it as a new edit of the retained draft.
        suppressDraftEdits = true
        sourceView.text.clear()
        if (!isChangingConfigurations) operation.close()
        scope.cancel() // QR rendering is reproducible from the retained ciphertext.
        super.onDestroy()
    }

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

        val privacyNote = if (operation.draft.isEmpty())
            "在此输入后，只将密文分享给聊天应用。请使用你信任的输入法，它仍能读取键入内容。"
        else "从其他应用分享或粘贴的文字，来源应用已接触过原文。下次可直接在文图易中输入，再分享密文。"
        root.addView(subtle(privacyNote), matchWrapWithTop(12))
        root.addView(subtle("要加密的文字"), matchWrapWithTop(16))
        // Editable, not a read-only preview: text arriving from a share sheet usually needs
        // trimming, and the hub's "写一段文字加密" entry starts here with nothing at all.
        sourceView = EditText(this).apply {
            isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            setText(operation.draft)
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

        encryptButton = accentButton("加密") { encrypt() }
        root.addView(encryptButton, matchWrapWithTop(16))

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

        sourceView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!busy && !suppressDraftEdits) {
                    operation.editDraft(s?.toString().orEmpty())
                    invalidateResult()
                }
            }
        })

        setContentView(scroll)
    }

    private fun selectedTarget(): SendTarget = targets.firstOrNull {
        when (it) {
            SendTarget.SharedPassphrase -> selection.isShared
            is SendTarget.Contact -> it.contact.fingerprint == selection.fingerprint
            is SendTarget.Unavailable -> false
        }
    } ?: SendTarget.Unavailable("所选目标已不可用，请重新选择")

    private fun refreshTargets() {
        targets = MessageEncryptor.availableTargets(this)
        if (!operation.selectionInitialized && targets.isNotEmpty()) {
            operation.select((targets.first() as? SendTarget.Contact)?.contact?.fingerprint)
        }
        targetButton.isEnabled = targets.isNotEmpty() && !busy
        targetButton.text = if (targets.isEmpty()) "尚未设置密钥"
            else MessageEncryptor.label(selectedTarget())
    }

    private fun showTargetPicker() {
        refreshTargets()
        if (targets.isEmpty()) return
        val choices = targets.toList()
        val labels = choices.map { MessageEncryptor.label(it) }.toTypedArray()
        val current = selectedTarget()
        AlertDialog.Builder(this)
            .setTitle("加密给谁")
            .setSingleChoiceItems(labels, choices.indexOf(current)) { dialog, which ->
                invalidateResult()
                operation.select((choices[which] as? SendTarget.Contact)?.contact?.fingerprint)
                refreshTargets()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── Encrypt ──────────────────────────────────────────────────────────────

    private fun invalidateResult() {
        resultGeneration++
        payload = null
        actionsRow.visibility = View.GONE
        resultView.visibility = View.GONE
        qrContainer.removeAllViews()
    }

    private fun encrypt() {
        if (busy) return
        if (operation.draft.isEmpty()) { statusView.text = "没有可加密的文字"; return }
        refreshTargets()
        val target = selectedTarget()
        if (target is SendTarget.Unavailable) { statusView.text = target.reason; return }
        operation.encrypt(target)
    }

    private fun renderOperationState() {
        encryptButton.isEnabled = !busy
        sourceView.isEnabled = !busy
        if (sourceView.text.toString() != operation.draft) {
            suppressDraftEdits = true
            sourceView.setText(operation.draft)
            suppressDraftEdits = false
        }
        refreshTargets()
        when (val state = operation.state) {
            EncryptOperation.State.Idle -> Unit
            EncryptOperation.State.Busy -> {
                invalidateResult()
                statusView.text = "正在加密…"
            }
            is EncryptOperation.State.Failure -> {
                invalidateResult()
                statusView.text = "加密失败：${state.message}"
            }
            is EncryptOperation.State.Success -> {
                payload = state.encrypted.payload
                // PROCESS_TEXT returns the original operation's ciphertext even if its
                // Activity was replaced while the ratchet state was being persisted.
                if (canReplaceInPlace) finishWithReplacement(state.encrypted.payload)
                else showResult(state.encrypted)
            }
        }
    }

    private fun finishWithReplacement(ciphertext: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, ciphertext))
        finish()
    }

    private fun showResult(enc: MessageEncryptor.EncryptedText) {
        statusView.text = buildString {
            append("已加密。请选择分享文本、生成二维码，或复制密文。")
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
        actionsRow.addView(smallButton("复制密文") { copyToClipboard(payload) }, weight(1f))
        actionsRow.addView(smallButton("分享文本") { shareText(payload) }, weightWithLeft(1f, 10))
        actionsRow.addView(smallButton("生成二维码") { renderQr(payload) }, weightWithLeft(1f, 10))
    }

    private fun renderQr(payload: String) {
        val generation = resultGeneration
        statusView.text = "正在生成二维码…"
        scope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    ImageStore.saveEncryptedPayloadQr(this@EncryptActivity, payload)
                }
                if (generation != resultGeneration || this@EncryptActivity.payload != payload) return@launch
                qrContainer.removeAllViews()
                val preview = ImageView(this@EncryptActivity).apply { adjustViewBounds = true }
                qrContainer.addView(preview, matchWrapWithTop(10))
                // Keep one preview in memory. A 32-page message used to keep every
                // 2048-pixel bitmap alive in ImageViews, on top of the generated set.
                var page = 0
                val pageLabel = subtle("1/${uris.size}")
                fun showPage() {
                    preview.setImageURI(uris[page])
                    pageLabel.text = "${page + 1}/${uris.size}"
                }
                showPage()
                if (uris.size > 1) {
                    qrContainer.addView(pageLabel, matchWrapWithTop(6))
                    val pages = LinearLayout(this@EncryptActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    pages.addView(smallButton("上一张") {
                        page = (page + uris.size - 1) % uris.size
                        showPage()
                    }, weight(1f))
                    pages.addView(smallButton("下一张") {
                        page = (page + 1) % uris.size
                        showPage()
                    }, weightWithLeft(1f, 10))
                    qrContainer.addView(pages, matchWrapWithTop(6))
                }
                qrContainer.addView(
                    primaryButton(if (uris.size > 1) "分享 ${uris.size} 张二维码" else "分享二维码") {
                        if (uris.size > 1) IntentHelpers.shareImages(this@EncryptActivity, uris, "分享加密二维码")
                        else IntentHelpers.shareImage(this@EncryptActivity, uris[0], "分享加密二维码")
                    },
                    matchWrapWithTop(10),
                )
                statusView.text = if (uris.size > 1)
                    "内容较长，已拆成 ${uris.size} 张二维码，需全部发给对方"
                else "二维码已生成"
            } catch (e: Exception) {
                if (generation != resultGeneration || this@EncryptActivity.payload != payload) return@launch
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
