package com.wentuyi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/** Dense, deterministic cards exercise the finder independently of encryption randomness. */
@RunWith(AndroidJUnit4::class)
class QrDecoderReliabilityTest {
    private val render by lazy {
        TextImageCodec::class.java.getDeclaredMethod("encodeQrBitmap", String::class.java,
            String::class.java, String::class.java, String::class.java, Integer.TYPE).apply { isAccessible = true }
    }

    private fun content(seed: Long): String {
        val bytes = ByteArray(600).also { Random(seed).nextBytes(it) }
        return "WTYP1|abcdefgh|12|32|" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun card(content: String): Bitmap = render.invoke(TextImageCodec, content,
        "文图易加密文字 12/32", "扫码导入文图易解密", "WTYP1", 0) as Bitmap

    @Test fun denseCardsDecodeWithoutDependingOnFinderPatternLuck() {
        repeat(128) { sample ->
            val content = content(20260930L + sample)
            val bitmap = card(content)
            try {
                assertEquals(content, TextImageCodec.readQrText(bitmap))
            } catch (e: Exception) {
                // Synthetic bytes only. Save the actual failing layout so device failures
                // can be reproduced without recording a user's encrypted/plaintext message.
                val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                File(cache, "qr-finder-regression-content.txt").writeText(content)
                File(cache, "qr-finder-regression.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                throw AssertionError("dense-card sample=$sample seed=${20260930L + sample} failed", e)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun confirmedFalseFinderSampleSurvivesCleanJpegAndHalfSizeImports() {
        // Both Android and JVM ZXing 3.5.3 stopped at a false data center for this seed,
        // missing the real bottom-left finder. Six padding variants failed identically.
        val content = content(20260935L)
        val card = card(content)
        fun verifyAndRecycle(label: String, bitmap: Bitmap) {
            try { assertEquals(label, content, TextImageCodec.readQrText(bitmap)) }
            finally { bitmap.recycle() }
        }
        try {
            assertEquals("confirmed sample clean card", content, TextImageCodec.readQrText(card))
            verifyAndRecycle("confirmed sample pure QR", Bitmap.createBitmap(card, 40, 120, 2048, 2048))
            verifyAndRecycle("confirmed sample half card",
                Bitmap.createScaledBitmap(card, card.width / 2, card.height / 2, true))
            val bytes = ByteArrayOutputStream().also { assertTrue(card.compress(Bitmap.CompressFormat.JPEG, 70, it)) }
                .toByteArray()
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            try {
                assertEquals("confirmed sample JPEG 70", content, TextImageCodec.readQrText(jpeg))
                verifyAndRecycle("confirmed sample half JPEG 70",
                    Bitmap.createScaledBitmap(jpeg, jpeg.width / 2, jpeg.height / 2, true))
            } finally { jpeg.recycle() }
        } finally { card.recycle() }
    }

    @Test fun multipleDistinctFallbackCandidatesAreRejectedInsteadOfChoosingOneMessage() {
        val contents = listOf(content(20260935L), "WTYID1|diagnostic|" +
            Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP))
        val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.RGB_565)
        try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4)
            val row = IntArray(2048)
            contents.forEachIndexed { index, text ->
                val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 2048, 2048, hints)
                for (y in 0 until 2048) {
                    for (x in row.indices) row[x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    bitmap.setPixels(row, 0, row.size, index * 2048, y, row.size, 1)
                }
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
            val decodeHints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8")
            try {
                TextImageCodec.readUniqueQrCandidate(binary, decodeHints)
                fail("two distinct QR messages must not silently select the first")
            } catch (e: IllegalArgumentException) {
                assertTrue("receiver gets an actionable ambiguity error", e.message?.contains("多条不同二维码") == true)
            }
        } finally { bitmap.recycle() }
    }

}
