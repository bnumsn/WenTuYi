package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

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

    private val scope: CoroutineScope = MainScope()
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var imagesLayout: LinearLayout
    private var lastPlainText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

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
            setBackgroundColor(Color.rgb(247, 248, 243))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        SystemBarPadding.apply(root, dp(22), dp(18), dp(22), dp(22))
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "解密接收"
            setTextColor(Color.rgb(21, 24, 18))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())

        statusView = subtle("等待操作")
        root.addView(statusView, matchWrapWithTop(8))

        root.addView(primaryButton("从图库选择二维码图片") { pickQrImages() }, matchWrapWithTop(18))
        root.addView(primaryButton("解密剪贴板文字") { decryptClipboardText() }, matchWrapWithTop(10))
        root.addView(primaryButton("复制结果") { copyResult() }, matchWrapWithTop(10))

        resultView = TextView(this).apply {
            setTextColor(Color.rgb(21, 24, 18))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minLines = 7
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.WHITE)
            text = "等待解密内容…"
        }
        root.addView(resultView, matchWrapWithTop(18))

        imagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(imagesLayout, matchWrapWithTop(12))

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

    private fun decryptFromUris(uris: List<Uri>) {
        setBusy("正在识别二维码…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { decryptUrisBlocking(uris) }
                showResult(result)
            } catch (e: Exception) {
                showFailure("解密失败", e.userMessage())
            }
        }
    }

    private suspend fun decryptUrisBlocking(uris: List<Uri>): DecryptionResult {
        val bitmaps = withContext(Dispatchers.IO) {
            uris.map { BitmapUtils.decodeImportImage(contentResolver, it) }
        }
        val qrTexts = bitmaps.map { TextImageCodec.readQrText(it) }
        if (qrTexts.size > 1) {
            withContext(Dispatchers.Main) {
                statusView.text = "已识别 ${qrTexts.size} 张二维码，正在重组…"
            }
        }
        // Identity QR(s) take precedence — if the user picked an identity card, route to ScanActivity.
        if (qrTexts.any { it.startsWith("${KeyExchange.QR_PREFIX}|") }) {
            val identityText = qrTexts.first { it.startsWith("${KeyExchange.QR_PREFIX}|") }
            val (name, publicKey) = KeyExchange.decodeIdentityFromQr(identityText)
            val myIdentity = KeyExchange.getOrCreateIdentity(this)
            // Derive SAS first — ecdh() rejects low-order pubkeys. Don't persist a
            // contact whose math would never work.
            val sas = KeyExchange.shortAuthString(myIdentity, publicKey)
            KeyExchange.saveContact(this, KeyExchange.Contact(name, publicKey))
            return DecryptionResult(
                resultText = "已添加联系人：$name\n指纹：${
                    KeyExchange.Contact(name, publicKey).fingerprint
                }\n校验码 (请双方核对)：$sas",
                statusText = "联系人已保存",
                lastPlainText = null,
                images = emptyList()
            )
        }

        val payload = TextImageCodec.assemblePayloadFromTexts(qrTexts)
        return resultFromMessageDecryptor(MessageDecryptor.decrypt(this, payload))
    }

    private fun resultFromMessageDecryptor(result: MessageDecryptor.Result): DecryptionResult {
        return when (result) {
            is MessageDecryptor.Result.Success -> {
                val base = resultFromDecrypted(result.payload)
                if (result.sender != null) {
                    base.copy(statusText = "${base.statusText} · 来自 ${result.sender.name}")
                } else base
            }
            is MessageDecryptor.Result.Failure -> throw java.security.GeneralSecurityException(result.message)
        }
    }

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

    private fun decryptTextPayload(payload: String) {
        setBusy("正在解密文字…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    resultFromMessageDecryptor(MessageDecryptor.decrypt(this@DecryptActivity, payload))
                }
                showResult(result)
            } catch (e: Exception) {
                showFailure("解密失败", e.userMessage())
            }
        }
    }

    private fun resultFromDecrypted(decrypted: SecurePayloadCodec.DecryptedPayload): DecryptionResult {
        return if (decrypted.isText()) {
            val text = decrypted.text()
            DecryptionResult(text, "文字解密完成", text, emptyList())
        } else {
            val bitmap = BitmapUtils.decodeImageBytes(decrypted.data)
            DecryptionResult("已解密一张图片", "图片解密完成", null, listOf(bitmap))
        }
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
            setBackgroundColor(Color.WHITE)
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

    private data class DecryptionResult(
        val resultText: String,
        val statusText: String,
        val lastPlainText: String?,
        val images: List<Bitmap>,
    )

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(95, 102, 90))
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

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
