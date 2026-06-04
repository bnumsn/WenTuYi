package com.wentuyi.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only content provider serving PNGs from our private cache.
 *
 * v3 widens the supported projection to include `_data` and `MediaStore.MediaColumns.MIME_TYPE`
 * because some chat hosts (notably WeChat) probe those columns and refuse to ingest the
 * URI when they come back null — earlier versions silently failed in that case.
 */
class ImageContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.wentuyi.app.images"
        const val CACHE_DIR = "wentuyi-images"

        @JvmStatic
        fun uriForFile(file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.name)
                .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = try { resolveFile(uri) } catch (e: FileNotFoundException) { return null }
        val columns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val row = arrayOfNulls<Any>(columns.size)
        for (i in columns.indices) {
            row[i] = when (columns[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                MediaStore.MediaColumns.MIME_TYPE -> "image/png"
                MediaStore.MediaColumns.DATA -> file.absolutePath
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains("w")) throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Throws(FileNotFoundException::class)
    private fun resolveFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val name = uri.lastPathSegment
            ?: throw FileNotFoundException("Bad image name")
        if (name.contains("/") || name.contains(".."))
            throw FileNotFoundException("Bad image name")
        val file = File(File(ctx.cacheDir, CACHE_DIR), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return file
    }
}
