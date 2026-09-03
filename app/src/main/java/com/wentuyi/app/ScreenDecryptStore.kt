package com.wentuyi.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock

object ScreenDecryptStore {
    private const val PREFS = "screen_decrypt_result"
    private const val KEY_HAS_RESULT = "has_result"
    private const val KEY_CREATED_AT = "created_at"
    private const val STALE_MS = 2 * 60 * 1000L

    fun save(context: Context, result: Intent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_RESULT, true)
            .putLong(KEY_CREATED_AT, SystemClock.elapsedRealtime())
            .putBoolean(
                ScreenDecryptActivity.EXTRA_OK,
                result.getBooleanExtra(ScreenDecryptActivity.EXTRA_OK, false),
            )
            .putString(ScreenDecryptActivity.EXTRA_KIND, result.getStringExtra(ScreenDecryptActivity.EXTRA_KIND))
            .putString(ScreenDecryptActivity.EXTRA_TEXT, result.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT))
            .putString(
                ScreenDecryptActivity.EXTRA_IMAGE_URI,
                result.getStringExtra(ScreenDecryptActivity.EXTRA_IMAGE_URI),
            )
            .putString(
                ScreenDecryptActivity.EXTRA_MESSAGE,
                result.getStringExtra(ScreenDecryptActivity.EXTRA_MESSAGE),
            )
            .apply()
    }

    fun consume(context: Context): Intent? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_RESULT, false)) return null
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        val stale = createdAt <= 0L || SystemClock.elapsedRealtime() - createdAt > STALE_MS
        val ok = prefs.getBoolean(ScreenDecryptActivity.EXTRA_OK, false)
        val kind = prefs.getString(ScreenDecryptActivity.EXTRA_KIND, null)
        val text = prefs.getString(ScreenDecryptActivity.EXTRA_TEXT, null)
        val imageUri = prefs.getString(ScreenDecryptActivity.EXTRA_IMAGE_URI, null)
        val message = prefs.getString(ScreenDecryptActivity.EXTRA_MESSAGE, null)
        clear(context)
        if (stale) return null

        return Intent(ScreenDecryptActivity.ACTION_RESULT).setPackage(context.packageName)
            .putExtra(ScreenDecryptActivity.EXTRA_OK, ok)
            .apply {
                kind?.let { putExtra(ScreenDecryptActivity.EXTRA_KIND, it) }
                text?.let { putExtra(ScreenDecryptActivity.EXTRA_TEXT, it) }
                imageUri?.let { putExtra(ScreenDecryptActivity.EXTRA_IMAGE_URI, it) }
                message?.let { putExtra(ScreenDecryptActivity.EXTRA_MESSAGE, it) }
            }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
