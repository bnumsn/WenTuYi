package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.app.Activity
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
 * Generic QR scanner: pick an image (gallery or any image-supplying chooser), decode
 * via ZXing, then route by content type:
 *   • Identity QR (`WTYID1|…`) → save as a [KeyExchange.Contact] and show the SAS to
 *     verify against the peer's screen.
 *   • Encrypted payload QR (single or multi-page) → forward to [DecryptActivity].
 *
 * Camera capture is offered via `ACTION_IMAGE_CAPTURE` — staying on the AOSP-only path
 * means we don't need CameraX (AndroidX) just for occasional QR scans. The trade-off
 * is one extra tap (open camera app → snap → return) which is acceptable for the
 * once-per-relationship flow of key exchange.
 */
class ScanActivity : Activity() {

    companion object {
        private const val REQ_PICK = 301
        private const val REQ_CAPTURE = 302
        private const val REQ_CAMERA = 303
    }

    private val scope: CoroutineScope = MainScope()
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var preview: ImageView
    private var captureUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        buildUi()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK -> {
                val uris = IntentHelpers.getSelectedImageUris(data)
                if (uris.isNotEmpty()) scanAll(uris)
            }
            REQ_CAPTURE -> captureUri?.let { scanAll(listOf(it)) }
            REQ_CAMERA -> data?.getStringExtra(CameraScanActivity.EXTRA_QR_TEXT)?.let { text ->
                statusView.text = "已扫到二维码，正在识别…"
                scope.launch { routeScannedTexts(listOf(text)) }
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

        root.addView(TextView(this).apply {
            text = "扫码 / 导入二维码"
            setTextColor(Palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())

        statusView = subtle("选择二维码图片，文图易会自动识别是身份码还是加密内容。")
        root.addView(statusView, matchWrapWithTop(8))

        root.addView(button("实时扫码（相机）") { launchCameraScan() }, matchWrapWithTop(18))
        root.addView(button("从图库选择") { pickFromGallery() }, matchWrapWithTop(10))

        preview = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Palette.card)
            visibility = View.GONE
        }
        root.addView(preview, matchWrapWithTop(16))

        resultView = TextView(this).apply {
            setTextColor(Palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Palette.card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minLines = 5
            text = "等待扫码…"
        }
        root.addView(resultView, matchWrapWithTop(12))

        setContentView(scroll)
    }

    private fun pickFromGallery() {
        IntentHelpers.pickImage(this, REQ_PICK, allowMultiple = true, "选择二维码图片")
    }

    private fun launchCameraScan() {
        startActivityForResult(Intent(this, CameraScanActivity::class.java), REQ_CAMERA)
    }

    private fun scanAll(uris: List<Uri>) {
        statusView.text = "正在识别二维码…"
        scope.launch {
            try {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.map { BitmapUtils.decodeImportImage(contentResolver, it) }
                }
                preview.setImageBitmap(bitmaps.first())
                preview.visibility = View.VISIBLE

                val texts = withContext(Dispatchers.Default) {
                    bitmaps.map { TextImageCodec.readQrText(it) }
                }
                routeScannedTexts(texts)
            } catch (e: Exception) {
                statusView.text = "识别失败"
                resultView.text = e.message ?: e::class.java.simpleName
            }
        }
    }

    private suspend fun routeScannedTexts(texts: List<String>) {
        val identityText = texts.firstOrNull { it.startsWith("${KeyExchange.QR_PREFIX}|") }
        if (identityText != null) {
            handleIdentity(identityText); return
        }
        // Otherwise treat as encrypted payload (single or multi-chunk).
        try {
            val payload = TextImageCodec.assemblePayloadFromTexts(texts)
            val decrypted = withContext(Dispatchers.Default) { decryptPayloadAuto(payload) }
            if (decrypted.isText()) {
                resultView.text = decrypted.text()
                statusView.text = "文字解密完成"
            } else {
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapUtils.decodeImageBytes(decrypted.data)
                }
                preview.setImageBitmap(bitmap)
                resultView.text = "已解密一张图片，请保存或转发。"
                statusView.text = "图片解密完成"
            }
        } catch (e: Exception) {
            statusView.text = "解析失败"
            resultView.text = e.message ?: e::class.java.simpleName
        }
    }

    private fun handleIdentity(identityText: String) {
        try {
            val (name, publicKey) = KeyExchange.decodeIdentityFromQr(identityText)
            // verified=false by default — added contacts are "preliminary" until both
            // sides confirm the SAS out-of-band. The IME warns when sending to an
            // unverified target.
            val contact = KeyExchange.Contact(name, publicKey, verified = false)
            val identity = KeyExchange.getOrCreateIdentity(this)
            // Derive the SAS first — KeyExchange.ecdh() refuses low-order public keys
            // and throws IllegalArgumentException. Persisting the contact before this
            // check (v0.5 behaviour) left an attacker's pubkey in the contact list
            // even though the session never could have worked.
            val sas = KeyExchange.shortAuthString(identity, publicKey)
            KeyExchange.saveContact(this, contact)
            statusView.text = "联系人已保存 (未验证)"
            resultView.text = buildString {
                append("已添加联系人：$name\n")
                append("指纹：${contact.fingerprint}\n\n")
                append("校验码 (SAS)：$sas\n\n")
                append("⚠ 这只是\"初步加好友\"。要确保不是中间人攻击：\n")
                append("1) 让对方也扫你的身份码 (主页 → 我的身份码)\n")
                append("2) 双方设备上看到的 8 位数字应一致\n")
                append("3) 通过电话/当面比对（不要通过同一聊天 App）\n")
                append("4) 比对一致后到「身份与密钥」点「标记已验证」\n\n")
                append("未验证的联系人能发送，但 IME 会持续提醒。")
            }
        } catch (e: Exception) {
            statusView.text = "身份码格式异常"
            resultView.text = e.message ?: e::class.java.simpleName
        }
    }

    /** Routes via [MessageDecryptor] for parity with [DecryptActivity]. */
    private fun decryptPayloadAuto(payload: String): SecurePayloadCodec.DecryptedPayload =
        when (val r = MessageDecryptor.decrypt(this, payload)) {
            is MessageDecryptor.Result.Success -> r.payload
            is MessageDecryptor.Result.Failure -> throw GeneralSecurityException(r.message)
        }

    // ─── UI helpers ─────────────────────────────────────────────────────────

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Palette.textSubtle)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
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
