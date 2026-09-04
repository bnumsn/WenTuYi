package com.wentuyi.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes a PNG into our private cache directory and hands back a content:// URI
 * owned by [ImageContentProvider]. Prunes stale files on each save so the cache can't
 * grow unboundedly when chat apps never request the file.
 *
 * Two lifetimes, because the files are not equally sensitive:
 *  - **Outbound** ([savePng]) — the ciphertext QR or the text-image the user is about to
 *    share. A receiving app may fetch the URI long after the share sheet opened (drafts,
 *    "send later", background upload), so these get a generous 24 h.
 *  - **Decrypted** ([saveDecryptedPng]) — plaintext that only exists because the user just
 *    decrypted something. Leaving it on disk for a day would undo the point of the app, so
 *    it gets minutes: long enough to view, copy or insert it, not long enough to become a
 *    forensic artifact. Nothing outside this process ever needs it.
 */
object ImageStore {
    private const val OUTBOUND_PREFIX = "wty_"
    private const val DECRYPTED_PREFIX = "wtytmp_"
    private const val MAX_FILE_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_DECRYPTED_AGE_MS = 10L * 60 * 1000

    @JvmStatic
    @Throws(IOException::class)
    fun savePng(context: Context, bitmap: Bitmap): Uri = write(context, bitmap, OUTBOUND_PREFIX)

    /** Same as [savePng] but for plaintext we just decrypted — pruned after minutes. */
    @JvmStatic
    @Throws(IOException::class)
    fun saveDecryptedPng(context: Context, bitmap: Bitmap): Uri =
        write(context, bitmap, DECRYPTED_PREFIX)

    private fun write(context: Context, bitmap: Bitmap, prefix: String): Uri {
        val dir = File(context.cacheDir, ImageContentProvider.CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建图片缓存目录")
        }
        pruneOld(dir)
        val file = File.createTempFile(prefix, ".png", dir)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("图片编码失败")
            }
        }
        return ImageContentProvider.uriForFile(file)
    }

    /**
     * Drops expired files of both classes. Called on every save; the decrypt paths also
     * call it directly ([pruneNow]) so a session that decrypts once and never saves again
     * still gets its plaintext cleaned up on the next app interaction.
     */
    @JvmStatic
    fun pruneNow(context: Context) {
        pruneOld(File(context.cacheDir, ImageContentProvider.CACHE_DIR))
    }

    private fun pruneOld(dir: File) {
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        for (f in files) {
            if (!f.isFile) continue
            val maxAge = when {
                // Check the more specific prefix first: "wtytmp_" also starts with "wty".
                f.name.startsWith(DECRYPTED_PREFIX) -> MAX_DECRYPTED_AGE_MS
                f.name.startsWith(OUTBOUND_PREFIX) -> MAX_FILE_AGE_MS
                else -> continue
            }
            if (now - f.lastModified() > maxAge) f.delete()
        }
    }
}
