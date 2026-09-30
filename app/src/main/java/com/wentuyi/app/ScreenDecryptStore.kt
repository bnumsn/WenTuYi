package com.wentuyi.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** Process-local, one-shot results. No plaintext or completion events cross a broadcast. */
object ScreenDecryptStore {
    private const val STALE_MS = 2 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private var requestId: String? = null
    private var startedAt = 0L
    private var lifetimeMs = STALE_MS
    private var processing = false
    private var pending: Intent? = null
    private var listener: (() -> Unit)? = null
    private val expire = Runnable { synchronized(this) { if (!processing) discard() } }

    private fun discard() {
        main.removeCallbacks(expire)
        requestId = null
        pending = null
        startedAt = 0L
        processing = false
    }

    @Synchronized
    fun begin(): String = begin(STALE_MS)

    @Synchronized
    internal fun begin(validForMillis: Long): String {
        require(validForMillis in 1..STALE_MS)
        discard()
        lifetimeMs = validForMillis
        startedAt = SystemClock.elapsedRealtime()
        main.postDelayed(expire, lifetimeMs)
        return UUID.randomUUID().toString().also { requestId = it }
    }

    @Synchronized
    fun isActive(id: String?): Boolean {
        if (!processing && requestId != null && SystemClock.elapsedRealtime() - startedAt >= lifetimeMs) discard()
        return id != null && id == requestId
    }

    /** Once a worker may consume a message key, expiry waits for its result. */
    @Synchronized
    fun beginProcessing(id: String?): Boolean {
        if (!isActive(id) || processing || pending != null) return false
        processing = true
        main.removeCallbacks(expire)
        return true
    }

    @Synchronized
    fun save(result: Intent) {
        if (!isActive(result.getStringExtra(ScreenDecryptActivity.EXTRA_REQUEST_ID)) || pending != null) return
        pending = Intent(result)
        processing = false
        lifetimeMs = STALE_MS
        startedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(expire)
        main.postDelayed(expire, lifetimeMs)
        main.post { synchronized(this) { listener }?.invoke() }
    }

    @Synchronized
    fun consume(id: String): Intent? {
        if (!isActive(id)) return null
        val result = pending ?: return null
        discard()
        return result
    }

    @Synchronized
    fun observe(callback: () -> Unit) { listener = callback }

    @Synchronized
    fun stopObserving(callback: () -> Unit) {
        if (listener === callback) listener = null
    }

    @Synchronized
    fun clear(context: Context) {
        discard()
        runCatching { ImageStore.pruneNow(context) }
    }
}
