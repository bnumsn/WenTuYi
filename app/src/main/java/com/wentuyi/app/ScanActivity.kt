package com.wentuyi.app

import android.app.Activity
import android.content.Intent
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
 * Generic QR scanner: pick an image (gallery or any image-supplying chooser), decode
 * via ZXing, then route by content type:
 *   • Identity QR (`WTYID1|…`) → save as a [KeyExchange.Contact] and show the full safety code to
 *     verify against the peer's screen.
 *   • Encrypted payload QR (single or multi-page) → retain and display the decrypted result.
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

    private lateinit var operation: DecryptOperation
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var preview: ImageView
    private var captureUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        ImageStore.pruneNow(this)
        captureUri = savedInstanceState?.getString("captureUri")?.let(Uri::parse)
        val retained = lastNonConfigurationInstance as? DecryptOperation
        operation = retained ?: DecryptOperation(applicationContext)
        buildUi()
        operation.attach(::renderState)
        if (retained == null && savedInstanceState != null) operation.restoredAfterProcessDeath()
    }

    override fun onRetainNonConfigurationInstance(): Any = operation

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureUri?.let { outState.putString("captureUri", it.toString()) }
    }

    override fun onDestroy() {
        operation.detach()
        if (!isChangingConfigurations) operation.close()
        super.onDestroy()
    }

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
                operation.decryptQrTexts(listOf(text))
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

    private fun scanAll(uris: List<Uri>) = operation.decryptImages(uris)

    private fun renderState(state: DecryptOperation.State) {
        when (state) {
            DecryptOperation.State.Idle -> Unit
            is DecryptOperation.State.Busy -> {
                statusView.text = state.message
                resultView.text = state.message
                preview.setImageDrawable(null)
                preview.visibility = View.GONE
            }
            is DecryptOperation.State.Failure -> {
                statusView.text = "解析失败"
                resultView.text = state.message
                preview.setImageDrawable(null)
                preview.visibility = View.GONE
            }
            is DecryptOperation.State.Success -> {
                statusView.text = state.result.statusText
                resultView.text = state.result.resultText
                val bitmap = state.result.images.firstOrNull()
                preview.setImageBitmap(bitmap)
                preview.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            }
        }
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
