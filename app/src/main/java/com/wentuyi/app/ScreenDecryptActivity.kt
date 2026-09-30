package com.wentuyi.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager

/**
 * Transparent, one-shot permission entry for the IME's "解" button.
 *
 * Android does not let an input method read another app's image bubbles directly.
 * MediaProjection is the system-sanctioned path: this Activity requests the user's
 * screen-capture consent, then hands the token to [ScreenDecryptService], which
 * performs the actual snapshot from a foreground service and returns the result
 * to the keyboard.
 */
class ScreenDecryptActivity : Activity() {

    companion object {
        const val EXTRA_REQUEST_ID = "screen_decrypt_request_id"
        const val EXTRA_OK = "ok"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEXT = "text"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_MESSAGE = "message"
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"

        private const val REQ_MEDIA_PROJECTION = 7141
    }

    private val requestId: String? get() = intent.getStringExtra(EXTRA_REQUEST_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (!ScreenDecryptStore.isActive(requestId)) { finish(); return }
        if (savedInstanceState == null) requestScreenCapture()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            finishFailure("未授予屏幕截图权限")
            return
        }
        // The service delays capture itself. Hand off immediately so a configuration
        // change cannot discard an already-granted one-shot projection token.
        try {
            requestId?.let { ScreenDecryptService.start(this, resultCode, data, it) }
            finish()
        } catch (e: Exception) {
            finishFailure("无法启动屏幕解密：${e.userMessage()}")
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finishFailure("系统不支持屏幕截图解密")
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            finishFailure("无法请求屏幕截图权限：${e.userMessage()}")
        }
    }

    private fun finishFailure(message: String) {
        val intent = Intent().putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_OK, false)
            .putExtra(EXTRA_MESSAGE, message)
        ScreenDecryptStore.save(intent)
        finish()
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
