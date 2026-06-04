package com.wentuyi.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Shared bitmap I/O + bounded-size decode helpers used by every entry-point Activity. */
internal object BitmapUtils {
    const val MAX_IMPORT_SIDE = 8192
    const val MAX_IMPORT_PIXELS = 12_000_000L
    const val MAX_ENCRYPT_ORIGINAL_BYTES = 512_000
    const val MAX_ENCRYPT_SIDE = 1600
    const val MAX_ENCRYPT_PIXELS = 2_560_000L
    const val MAX_COMPRESSED_ENCRYPT_BYTES = 360_000

    @Throws(IOException::class)
    fun decodeImportImage(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).useNotNull("无法读取图片") { BitmapFactory.decodeStream(it, null, bounds) }
        validateBounds(bounds.outWidth, bounds.outHeight, "图片过大")
        // Force ARGB_8888 so ZXing's getPixels() works; some OEM decoders default to
        // Config.HARDWARE which is opaque to CPU readback.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return resolver.openInputStream(uri).useNotNull("无法读取图片") {
            BitmapFactory.decodeStream(it, null, options) ?: throw IllegalArgumentException("图片格式不支持")
        }
    }

    fun decodeImageBytes(imageBytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        validateBounds(bounds.outWidth, bounds.outHeight, "解密后的图片过大")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            ?: throw IllegalArgumentException("解密后的图片格式不支持")
    }

    @Throws(IOException::class)
    fun imageBytesForEncryption(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        readSmallImageBytes(resolver, uri, MAX_ENCRYPT_ORIGINAL_BYTES)?.let { original ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0
                && bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMPORT_PIXELS) {
                return original
            }
        }
        val bitmap = decodeScaledForEncryption(resolver, uri)
        val out = ByteArrayOutputStream()
        var encoded: ByteArray? = null
        var quality = 88
        while (quality >= 60) {
            out.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
                throw IOException("图片编码失败")
            encoded = out.toByteArray()
            if (encoded.size <= MAX_COMPRESSED_ENCRYPT_BYTES) break
            quality -= 7
        }
        return encoded?.takeIf { it.isNotEmpty() } ?: throw IOException("图片编码失败")
    }

    private fun readSmallImageBytes(resolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray? {
        resolver.openInputStream(uri).useNotNull("无法读取图片") { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun decodeScaledForEncryption(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).useNotNull("无法读取图片") { BitmapFactory.decodeStream(it, null, bounds) }
        validateBounds(bounds.outWidth, bounds.outHeight, "图片过大")

        var sample = 1
        while (bounds.outWidth / sample > MAX_ENCRYPT_SIDE
            || bounds.outHeight / sample > MAX_ENCRYPT_SIDE
            || (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_ENCRYPT_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri).useNotNull("无法读取图片") {
            BitmapFactory.decodeStream(it, null, options)
                ?: throw IllegalArgumentException("图片格式不支持")
        }
    }

    private fun validateBounds(width: Int, height: Int, tooLargeMessage: String) {
        require(width > 0 && height > 0) { "图片格式不支持" }
        val pixels = width.toLong() * height
        require(width <= MAX_IMPORT_SIDE && height <= MAX_IMPORT_SIDE && pixels <= MAX_IMPORT_PIXELS) {
            tooLargeMessage
        }
    }

    private inline fun <R> InputStream?.useNotNull(missing: String, block: (InputStream) -> R): R {
        val s = this ?: throw IllegalArgumentException(missing)
        return s.use(block)
    }
}
