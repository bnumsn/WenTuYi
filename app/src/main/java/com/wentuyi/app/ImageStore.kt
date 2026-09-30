package com.wentuyi.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Private PNG cache. Outbound images expire after 24 h; decrypted plaintext after
 * 10 min. One process-local timer deletes expired files while this process is alive.
 * Process restart and every provider read also check expiry; Android cannot run our
 * deletion code after killing the process. Plaintext is never copied into saved state.
 */
object ImageStore {
    private const val OUTBOUND_PREFIX = "wty_"
    private const val DECRYPTED_PREFIX = "wtytmp_"
    private const val MAX_FILE_AGE_MS = 24L * 60 * 60 * 1000
    internal const val MAX_DECRYPTED_AGE_MS = 10L * 60 * 1000
    private val cleanup = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "wentuyi-image-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var scheduledCleanup: ScheduledFuture<*>? = null

    @JvmStatic
    @Throws(IOException::class)
    fun savePng(context: Context, bitmap: Bitmap): Uri = write(context, bitmap, OUTBOUND_PREFIX)

    @JvmStatic
    @Throws(IOException::class)
    fun saveDecryptedPng(context: Context, bitmap: Bitmap): Uri =
        write(context, bitmap, DECRYPTED_PREFIX)

    /** Production QR send path: save and recycle each page before rendering the next. */
    fun saveEncryptedPayloadQr(context: Context, payload: String): List<Uri> {
        val uris = ArrayList<Uri>()
        try {
            TextImageCodec.forEachEncryptedPayloadQr(payload) { bitmap, _, _ ->
                uris += savePng(context, bitmap)
            }
            return uris
        } catch (e: Throwable) {
            uris.forEach { uri ->
                uri.lastPathSegment?.let { File(cacheDirectory(context), it).delete() }
            }
            throw e
        }
    }

    private fun write(context: Context, bitmap: Bitmap, prefix: String): Uri {
        val dir = cacheDirectory(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建图片缓存目录")
        pruneDirectory(dir)
        val file = File.createTempFile(prefix, ".png", dir)
        try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("图片编码失败")
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        pruneDirectory(dir)
        return ImageContentProvider.uriForFile(file)
    }

    @JvmStatic
    fun pruneNow(context: Context) = pruneDirectory(cacheDirectory(context))

    internal fun isExpired(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val ageLimit = ageLimit(file) ?: return false
        return now - file.lastModified() >= ageLimit
    }

    private fun cacheDirectory(context: Context) = File(context.cacheDir, ImageContentProvider.CACHE_DIR)

    private fun ageLimit(file: File): Long? = when {
        file.name.startsWith(DECRYPTED_PREFIX) -> MAX_DECRYPTED_AGE_MS
        file.name.startsWith(OUTBOUND_PREFIX) -> MAX_FILE_AGE_MS
        else -> null
    }

    /** A single timer per cache, with a bounded retry if a file cannot be deleted. */
    @Synchronized
    private fun pruneDirectory(dir: File) {
        scheduledCleanup?.cancel(false)
        scheduledCleanup = null
        val now = System.currentTimeMillis()
        var nextDelay = Long.MAX_VALUE
        for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val ageLimit = ageLimit(file) ?: continue
            val remaining = ageLimit - (now - file.lastModified())
            if (remaining <= 0) {
                if (!file.delete()) nextDelay = minOf(nextDelay, 60_000L)
            } else {
                nextDelay = minOf(nextDelay, remaining)
            }
        }
        if (nextDelay != Long.MAX_VALUE) {
            scheduledCleanup = cleanup.schedule({ pruneDirectory(dir) }, maxOf(1L, nextDelay), TimeUnit.MILLISECONDS)
        }
    }
}
