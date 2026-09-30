package com.wentuyi.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Decrypts incoming WTY4 / WTY5 / legacy payloads received via:
 *   • ACTION_SEND / ACTION_SEND_MULTIPLE forwarded from another app,
 *   • the system clipboard,
 *   • image-picker selection (e.g. multiple QR pages from one message).
 *
 * Multi-QR messages are reassembled in-memory before the single AES-GCM decryption.
 */
class DecryptActivity : Activity() {

    companion object {
        private const val REQ_PICK_IMAGES = 201
    }

    private lateinit var operation: DecryptOperation
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var imagesLayout: LinearLayout
    private var lastPlainText: String? = null
    private var encryptInsteadButton: Button? = null
    private lateinit var encryptInsteadHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        // Sweep any decrypted-plaintext PNG whose short TTL has expired.
        ImageStore.pruneNow(this)
        val retained = lastNonConfigurationInstance as? DecryptOperation
        operation = retained ?: DecryptOperation(applicationContext)
        buildUi()
        operation.attach(::renderState)
        when {
            retained != null -> Unit // Reattach to the original work/result; never consume again.
            savedInstanceState != null -> operation.restoredAfterProcessDeath()
            else -> handleIncomingIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onRetainNonConfigurationInstance(): Any = operation

    override fun onDestroy() {
        operation.detach()
        if (!isChangingConfigurations) operation.close()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = IntentHelpers.getSelectedImageUris(data)
        if (uris.isEmpty()) return
        if (requestCode == REQ_PICK_IMAGES) decryptFromUris(uris)
    }

    // ─── UI ─────────────────────────────────────────────────────────────────

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

        root.addView(TextView(this).apply {
            text = "解密接收"
            setTextColor(Palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())

        statusView = subtle("等待操作")
        root.addView(statusView, matchWrapWithTop(8))

        root.addView(primaryButton("从图库选择二维码图片") { pickQrImages() }, matchWrapWithTop(18))
        root.addView(primaryButton("解密剪贴板文字") { decryptClipboardText() }, matchWrapWithTop(10))
        root.addView(primaryButton("复制结果") { copyResult() }, matchWrapWithTop(10))

        resultView = TextView(this).apply {
            setTextColor(Palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minLines = 7
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Palette.card)
            text = "等待解密内容…"
        }
        root.addView(resultView, matchWrapWithTop(18))

        imagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(imagesLayout, matchWrapWithTop(12))

        // Host for the "encrypt this instead" recovery button (see offerEncryptInstead).
        encryptInsteadHost = root

        setContentView(scroll)
    }

    // ─── Incoming intent dispatch ───────────────────────────────────────────

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentHelpers.getStreamUris(intent)
                if (uris.isNotEmpty()) decryptFromUris(uris)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) decryptTextPayload(text.toString().trim())
                else IntentHelpers.getStreamUri(intent)?.let { decryptFromUris(listOf(it)) }
            }
        }
    }

    private fun pickQrImages() {
        IntentHelpers.pickImage(this, REQ_PICK_IMAGES, allowMultiple = true, title = "选择文图易二维码图片")
    }

    // ─── Decryption flows ───────────────────────────────────────────────────

    private fun decryptFromUris(uris: List<Uri>) = operation.decryptImages(uris)

    private fun decryptClipboardText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip: ClipData? = clipboard?.primaryClip
        if (clipboard == null || clip == null || clip.itemCount == 0) {
            statusView.text = "剪贴板为空"; return
        }
        val text = clip.getItemAt(0).coerceToText(this)
        if (text.isNullOrEmpty()) { statusView.text = "剪贴板没有文字"; return }
        decryptTextPayload(text.toString().trim())
    }

    private fun decryptTextPayload(payload: String) = operation.decryptText(payload)

    private fun renderState(state: DecryptOperation.State) {
        encryptInsteadButton?.let { encryptInsteadHost.removeView(it) }
        encryptInsteadButton = null
        when (state) {
            DecryptOperation.State.Idle -> Unit
            is DecryptOperation.State.Busy -> setBusy(state.message)
            is DecryptOperation.State.Success -> showResult(state.result)
            is DecryptOperation.State.Failure -> {
                showFailure("解密失败", state.message)
                state.encryptInstead?.let(::offerEncryptInstead)
            }
        }
    }

    private fun offerEncryptInstead(text: String) {
        encryptInsteadButton?.let { encryptInsteadHost.removeView(it) }
        val button = primaryButton("这不是密文 —— 改为加密这段文字") {
            startActivity(EncryptActivity.intentFor(this, text))
            finish()
        }
        encryptInsteadButton = button
        encryptInsteadHost.addView(button, matchWrapWithTop(12))
    }

    // ─── UI updates ─────────────────────────────────────────────────────────

    private fun showResult(result: DecryptionResult) {
        lastPlainText = result.lastPlainText
        clearImages()
        resultView.text = result.resultText
        statusView.text = result.statusText
        for (b in result.images) addResultImage(b)
    }

    private fun showFailure(label: String, message: String) {
        lastPlainText = null
        clearImages()
        resultView.text = "$label：$message"
        statusView.text = label
    }

    private fun setBusy(message: String) {
        lastPlainText = null
        clearImages()
        resultView.text = message
        statusView.text = message
    }

    private fun clearImages() {
        imagesLayout.removeAllViews()
        imagesLayout.visibility = View.GONE
    }

    private fun addResultImage(bitmap: Bitmap) {
        val view = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Palette.card)
            setImageBitmap(bitmap)
        }
        val params = matchWrap()
        if (imagesLayout.childCount > 0) params.topMargin = dp(10)
        imagesLayout.addView(view, params)
        imagesLayout.visibility = View.VISIBLE
    }

    private fun copyResult() {
        val text = lastPlainText
        if (text.isNullOrEmpty()) { statusView.text = "没有可复制的结果"; return }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("文图易解密文本", text))
            statusView.text = "结果已复制"
        } else statusView.text = "无法访问剪贴板"
    }

    // ─── Local types + helpers ──────────────────────────────────────────────

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Palette.textSubtle)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun primaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { action() }
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
