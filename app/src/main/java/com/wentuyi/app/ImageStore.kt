package com.wentuyi.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes a PNG into our private cache directory and hands back a content:// URI
 * owned by [ImageContentProvider]. Prunes files older than 24 h on each save to
 * stop the cache growing unboundedly when chat apps never request the file.
 */
object ImageStore {
    private const val MAX_FILE_AGE_MS = 24L * 60 * 60 * 1000

    @JvmStatic
    @Throws(IOException::class)
    fun savePng(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, ImageContentProvider.CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建图片缓存目录")
        }
        pruneOld(dir)
        val file = File.createTempFile("wty_", ".png", dir)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("图片编码失败")
            }
        }
        return ImageContentProvider.uriForFile(file)
    }

    private fun pruneOld(dir: File) {
        val files = dir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MS
        for (f in files) {
            if (f.isFile && f.name.startsWith("wty_") && f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }
}
