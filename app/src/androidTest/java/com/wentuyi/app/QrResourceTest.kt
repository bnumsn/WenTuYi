package com.wentuyi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wentuyi.protocol.SecurePayloadCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QrResourceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun maximumPageMessageUsesTheProductionSaveAndSequentialImportPaths() {
        val plaintext = buildString { repeat(19_000) { append(('!'.code + it % 90).toChar()) } }
        val payload = SecurePayloadCodec.encryptTextToPayload(plaintext, WentuyiSettings.getPassphrase(context))
        val uris = ImageStore.saveEncryptedPayloadQr(context, payload)
        assertEquals("exercise the maximum supported page count", 32, uris.size)
        val complete = CountDownLatch(1)
        var state: DecryptOperation.State? = null
        lateinit var operation: DecryptOperation
        instrumentation.runOnMainSync {
            operation = DecryptOperation(context)
            operation.attach {
                if (it is DecryptOperation.State.Success || it is DecryptOperation.State.Failure) {
                    state = it
                    complete.countDown()
                }
            }
            operation.decryptImages(uris)
        }
        try {
            assertTrue("all pages were decoded", complete.await(120, TimeUnit.SECONDS))
            val result = state as? DecryptOperation.State.Success
                ?: throw AssertionError("sequential import failed: $state")
            assertEquals(plaintext, result.result.lastPlainText)
            val bitmap = BitmapUtils.decodeQrImportImage(context.contentResolver, uris.first())
            try {
                assertTrue(bitmap.width.toLong() * bitmap.height <= BitmapUtils.MAX_QR_IMPORT_PIXELS)
            } finally { bitmap.recycle() }
        } finally {
            instrumentation.runOnMainSync { operation.close() }
            uris.forEach { File(File(context.cacheDir, ImageContentProvider.CACHE_DIR), it.lastPathSegment!!).delete() }
        }
    }

    @Test fun streamedPagesAreRecycledAndStillSurviveJpegRecompression() {
        val payload = SecurePayloadCodec.encryptTextWithSessionKey("jpeg-page-".repeat(140), ByteArray(32) { 7 })
        val texts = ArrayList<String>()
        var previous: Bitmap? = null
        TextImageCodec.forEachEncryptedPayloadQr(payload) { bitmap, _, _ ->
            previous?.let { assertTrue("previous full-size page was released", it.isRecycled) }
            val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)) }
            val jpeg = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
            try { texts += TextImageCodec.readQrText(jpeg) } finally { jpeg.recycle() }
            previous = bitmap
        }
        assertTrue(previous!!.isRecycled)
        assertEquals(payload, TextImageCodec.assemblePayloadFromTexts(texts))
    }

    @Test fun decryptedFileIsDeletedByTimerWithoutAnotherInteraction() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val uri = try { ImageStore.saveDecryptedPng(context, bitmap) } finally { bitmap.recycle() }
        val file = File(File(context.cacheDir, ImageContentProvider.CACHE_DIR), uri.lastPathSegment!!)
        assertTrue(file.setLastModified(System.currentTimeMillis() - ImageStore.MAX_DECRYPTED_AGE_MS + 500))
        ImageStore.pruneNow(context) // Restart the same production timer for the shortened remaining life.
        val deadline = SystemClock.uptimeMillis() + 5000
        while (file.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertFalse("timer deletes the file without a later save/open", file.exists())
    }

    @Test fun providerRejectsAnExpiredPlaintextFileEvenBeforeTheTimerRuns() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val uri = try { ImageStore.saveDecryptedPng(context, bitmap) } finally { bitmap.recycle() }
        val file = File(File(context.cacheDir, ImageContentProvider.CACHE_DIR), uri.lastPathSegment!!)
        assertTrue(file.setLastModified(System.currentTimeMillis() - ImageStore.MAX_DECRYPTED_AGE_MS - 1))
        try {
            context.contentResolver.openInputStream(uri)?.use { fail("expired plaintext was readable") }
            fail("expired URI should fail")
        } catch (_: FileNotFoundException) { /* expected */ }
        assertFalse(file.exists())
    }
}
