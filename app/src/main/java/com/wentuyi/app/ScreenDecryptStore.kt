package com.wentuyi.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Hands a screen-decrypt result from [ScreenDecryptService] to whichever component picks it
 * up next — normally the IME's decrypt panel, which may not have been listening when the
 * broadcast fired (the user was still in the projection consent dialog).
 *
 * **In memory only, on purpose.** This carries *decrypted plaintext*: the message the user
 * ran the whole app to protect. An earlier version parked it in SharedPreferences, which
 * meant a decrypted message could sit in a world-readable-to-root XML file indefinitely
 * whenever nobody consumed it — exactly the artifact this app exists to avoid. The service
 * and the IME live in the same process, so a static holder reaches the consumer just as
 * well, and if the process dies the plaintext dies with it. Losing a pending result to a
 * process death is the correct trade: the user simply taps 解图 again.
 */
object ScreenDecryptStore {
    private const val STALE_MS = 2 * 60 * 1000L

    private var pending: Intent? = null
    private var createdAt = 0L

    @Synchronized
    fun save(context: Context, result: Intent) {
        pending = Intent(result)
        createdAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun consume(context: Context): Intent? {
        val result = pending ?: return null
        val stale = createdAt <= 0L || SystemClock.elapsedRealtime() - createdAt > STALE_MS
        clear(context)
        return if (stale) null else result
    }

    @Synchronized
    fun clear(context: Context) {
        pending = null
        createdAt = 0L
        // Opportunistic sweep of expired plaintext PNGs. Deliberately TTL-respecting rather
        // than deleting this result's own file: consume() hands the URI to the panel that is
        // about to render it, so it has to outlive this call by a few minutes.
        runCatching { ImageStore.pruneNow(context) }
    }
}
