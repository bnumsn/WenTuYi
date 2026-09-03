package com.wentuyi.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        const val ACTION_RESULT = "com.wentuyi.app.SCREEN_DECRYPT_RESULT"
        const val EXTRA_OK = "ok"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEXT = "text"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_MESSAGE = "message"
        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"

        private const val REQ_MEDIA_PROJECTION = 7141
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        requestScreenCapture()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            finishFailure("未授予屏幕截图权限")
            return
        }
        // Give the system dialog/app-picker a short moment to disappear so the
        // foreground service captures the chat screen, not the permission UI.
        handler.postDelayed({
            ScreenDecryptService.start(this, resultCode, data)
            finish()
        }, 150L)
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
        val intent = Intent(ACTION_RESULT).setPackage(packageName)
            .putExtra(EXTRA_OK, false)
            .putExtra(EXTRA_MESSAGE, message)
        ScreenDecryptStore.save(this, intent)
        sendBroadcast(intent)
        finish()
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
