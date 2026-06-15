package com.wentuyi.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom

/**
 * QR-Code-based codec for 文图易 v3.
 *
 * Replaces the v2 self-rolled `WTYBW2/Dense/Grid` raster which was destroyed by JPEG
 * re-compression in any mainstream IM. All encrypted artefacts are now real QR codes
 * (error-correction H, byte mode) drawn at a high pixel density, with manual chunking
 * across multiple QRs when a single code would overflow.
 *
 * Wire formats produced by this codec:
 *   • single QR: payload is literally "WTY3:..." (a [SecurePayloadCodec] envelope) or
 *                "WTYID1|<name>|<base64-public-key>" (a [KeyExchange] identity).
 *   • multi-QR : each QR carries `WTYP1|<id>|<N>|<T>|<chunk>` where chunks
 *                concatenate (in 1..T order) back to the original "WTY3:..." payload.
 *                Encryption happens once; chunking is text-only at the transport layer.
 */
object TextImageCodec {
    // ─── QR rendering constants ───────────────────────────────────────────────
    // 2048 — large enough that ZXing rounds to multiple=11 px/module at QR v40
    // (vs multiple=6 at 1200). With 11 px/module the finder pattern is so over-
    // sampled that decode success rate jumps from ~93% to >99% per chunk, which
    // is the only setting where 6-chunk multi-QR is reliably >95% end-to-end.
    private const val QR_MATRIX_PIXEL = 2048
    private const val MAX_QR_BYTES = 800      // QR v40 ECC=H byte mode budget
    /**
     * Hard cap on multi-QR chunk count. 32 × 800 bytes ≈ 25 KB plaintext upper bound
     * for a single chat message — well above any realistic text payload, and well
     * below the integer-overflow-class attacks a hostile sender could trigger by
     * advertising total = Int.MAX_VALUE.
     */
    private const val MAX_QR_PAGES = 32

    // ─── Multi-QR chunking wrapper ────────────────────────────────────────────
    const val MULTI_PREFIX = "WTYP1"
    private const val DELIM = "|"

    // ─── Plain-text image (preserved from v2 — pretty PNG, no encryption) ─────
    private const val PLAIN_WIDTH = 1080
    private const val PLAIN_PADDING = 72

    // ─── Anti-OCR plain image (readable to humans, noisy to machine OCR) ───────
    private const val ANTIOCR_WIDTH = 1080
    private const val ANTIOCR_PADDING = 64
    private const val ANTIOCR_NOISE = 50

    private val random = SecureRandom()

    /** Renders a styled PNG showing [text]. Used by the IME "图" (plain image) action. */
    fun renderPlainTextImage(text: String): Bitmap =
        renderStyledTextImage(
            text = text,
            title = "文图易",
            width = PLAIN_WIDTH,
            padding = PLAIN_PADDING,
            textSize = 44f,
            titleSize = 42f,
            minHeight = 520,
            titleBaseline = 82f,
            ruleY = 116f,
            textTop = 174f,
            lineExtra = 18,
            bottomPadding = 56
        )

    /**
     * Renders [text] as a **plaintext** PNG that stays human-readable but is noisy and
     * jittered to make machine OCR / automated scraping harder. This is NOT encryption —
     * anyone can read it; use the WTY3 paths when you need confidentiality. Ported and
     * hardened from the v1.0 prototype's AntiOcrRenderer (adds CJK wrapping + size cap).
     */
    fun renderAntiOcrTextImage(text: String): Bitmap {
        require(text.isNotEmpty()) { "没有文字" }
        return renderAntiOcr(text, ANTIOCR_NOISE)
    }

    // ─── High-level: encrypt text/image bytes → list of QR bitmaps ────────────

    fun renderEncryptedTextAsQr(text: String, passphrase: String): List<Bitmap> =
        retryEncrypt { renderPayloadAsQr(
            SecurePayloadCodec.encryptTextToPayload(text, passphrase),
            "文图易加密文字", "扫码导入文图易解密") }

    fun renderEncryptedTextAsQr(text: String, sessionKey: ByteArray): List<Bitmap> =
        retryEncrypt { renderPayloadAsQr(
            SecurePayloadCodec.encryptTextWithSessionKey(text, sessionKey),
            "文图易加密文字", "扫码导入文图易解密") }

    fun renderEncryptedImageBytesAsQr(imageBytes: ByteArray, passphrase: String): List<Bitmap> =
        retryEncrypt { renderPayloadAsQr(
            SecurePayloadCodec.encryptImageToPayload(imageBytes, passphrase),
            "文图易加密图片", "扫码导入文图易解密") }

    fun renderEncryptedImageBytesAsQr(imageBytes: ByteArray, sessionKey: ByteArray): List<Bitmap> =
        retryEncrypt { renderPayloadAsQr(
            SecurePayloadCodec.encryptImageWithSessionKey(imageBytes, sessionKey),
            "文图易加密图片", "扫码导入文图易解密") }

    /**
     * Outer retry harness: if any chunk in the rendered set fails the per-bitmap
     * self-decode check, throw out of [renderPayloadAsQr] and rerun [block], which
     * re-encrypts with a fresh random IV/salt. Different ciphertext usually dodges
     * the specific ZXing-unreadable module patterns that occasionally crop up.
     * Five outer attempts gives ~99.9% reliability against ~6% per-chunk encode
     * flakiness even at 6-chunk messages.
     */
    private inline fun retryEncrypt(block: () -> List<Bitmap>): List<Bitmap> {
        var lastError: Exception? = null
        repeat(5) {
            try { return block() } catch (e: IllegalStateException) { lastError = e }
        }
        throw lastError ?: IllegalStateException("无法生成可解码的二维码")
    }

    /** Splits a long payload string across multiple QRs with a transport wrapper. */
    private fun renderPayloadAsQr(payload: String, title: String, footer: String): List<Bitmap> {
        val chunks = chunkPayload(payload)
        if (chunks.size == 1) {
            return listOf(encodeQrBitmapValidated(chunks[0], title, footer, payloadLabel(payload)))
        }
        return chunks.mapIndexed { i, chunk ->
            val pageLabel = "${i + 1}/${chunks.size}"
            encodeQrBitmapValidated(chunk, "$title $pageLabel", footer, "WTYP1")
        }
    }

    /**
     * Wraps [encodeQrBitmap] with an "encode → decode → bail if mismatch" self-check.
     * ZXing's own decoder occasionally fails on QRs we just rendered — possibly a
     * specific module pattern that triggers a finder-pattern false negative. The
     * QR content itself is unchanged (chunk-id is payload-derived so retries would
     * give the same content); the failure is purely in rendering. Since we can't
     * predict which content triggers it, we render then immediately verify, and if
     * verification fails we retry by perturbing the panel dimensions slightly — a
     * different bitmap geometry shifts the QR module-to-pixel alignment enough to
     * dodge the bad case. Hard cap of 3 attempts; the third just returns whatever
     * we got, so the receiver may have to ask the sender to resend.
     */
    private fun encodeQrBitmapValidated(content: String, title: String, footer: String, meta: String): Bitmap {
        // Bumped to 6 paddingShift attempts (was 3). Identity QRs can't fall back
        // to "re-encrypt with new random IV" the way encrypted payloads can — same
        // public key always renders to the same QR matrix — so paddingShift is the
        // only knob. 6 different geometries gives enough variation to dodge the
        // residual ZXing-unreadable cases observed in 100-run testing.
        repeat(6) { attempt ->
            val bm = encodeQrBitmap(content, title, footer, meta, attempt)
            try {
                if (readQrText(bm) == content) return bm
            } catch (e: Exception) { /* retry with shifted padding */ }
        }
        throw IllegalStateException("生成的二维码自解码失败 (content length=${content.length})")
    }

    private fun chunkPayload(payload: String): List<String> {
        if (payload.length <= MAX_QR_BYTES) return listOf(payload)
        val total = (payload.length + MAX_QR_BYTES - 1) / MAX_QR_BYTES
        require(total <= MAX_QR_PAGES) {
            // Realistically this trips only on encrypted images (the image-bytes-as-QR
            // path uses the same chunker). Caller should pre-shrink the image.
            "消息过长：拆分后需 $total 张二维码，超过上限 $MAX_QR_PAGES 张"
        }
        val id = chunkIdFor(payload)
        return (0 until total).map { i ->
            val start = i * MAX_QR_BYTES
            val end = minOf(start + MAX_QR_BYTES, payload.length)
            "$MULTI_PREFIX$DELIM$id$DELIM${i + 1}$DELIM$total$DELIM" + payload.substring(start, end)
        }
    }

    /**
     * Deterministic chunk id derived from `SHA-256(payload)[..5]` and Base32-encoded.
     * v0.4 used a 35-bit random id, which is fine for collision but doesn't bind the
     * id to the payload — so a malicious receiver swapping one chunk between two
     * messages with the same random id could splice them. v0.5 ties the id to the
     * payload's hash and re-verifies at assembly time.
     */
    private fun chunkIdFor(payload: String): String {
        val digest = CryptoUtils.sha256(payload.toByteArray(Charsets.UTF_8))
        return CryptoUtils.Base32.encode(digest.copyOf(5)).take(8).lowercase()
    }

    // ─── Identity QR (X25519 public key) ──────────────────────────────────────

    fun renderIdentityQr(identity: KeyExchange.Identity, name: String): Bitmap {
        val content = KeyExchange.encodeIdentityForQr(name, identity.publicKey)
        // Routed through encodeQrBitmapValidated so the identity QR also benefits
        // from the encode→decode self-check that the encrypted-text path uses.
        // Identity content is deterministic (no random IV), so paddingShift retries
        // are the only way to dodge an unreadable layout — but even short content
        // hit ZXing flakiness in 100-run testing (~2% per render).
        return encodeQrBitmapValidated(content, "文图易身份码",
            "扫码加入联系人 · 仅分享给可信对方", "WTYID1")
    }

    fun readIdentityQr(bitmap: Bitmap): Pair<String, ByteArray> {
        val text = readQrText(bitmap)
        return KeyExchange.decodeIdentityFromQr(text)
    }

    // ─── QR decoding ──────────────────────────────────────────────────────────

    /**
     * Decodes one QR from a bitmap. Tries three combinations in order:
     *   1. [HybridBinarizer] on the original bitmap
     *   2. [GlobalHistogramBinarizer] on the original bitmap
     *   3. [HybridBinarizer] on a 2x-downscaled copy (helps when JPEG noise has
     *      smeared individual modules; downscaling averages the noise out)
     *
     * v0.5.2 smoke testing showed the 2-binarizer fallback was insufficient for
     * Samsung's aggressive JPEG q=70 output — the third attempt cuts failure
     * rate from ~20% to <1%.
     */
    fun readQrText(bitmap: Bitmap): String {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val attempts: List<() -> com.google.zxing.Binarizer> = listOf(
            { HybridBinarizer(bitmapToLuminanceSource(bitmap)) },
            { GlobalHistogramBinarizer(bitmapToLuminanceSource(bitmap)) },
            { HybridBinarizer(bitmapToLuminanceSource(downscale(bitmap))) },
        )
        // Two full sweeps through the binarizer attempts: ZXing's TRY_HARDER path
        // exhibits ~5% non-deterministic failures on identical inputs (verified by
        // QrDeterminismTest), so a second pass roughly squares the residual fail
        // rate to ~0.25%. Six 0.25% chunks compound to ~98.5% multi-QR success.
        for (pass in 0..1) {
            for (attemptFactory in attempts) {
                try {
                    return QRCodeReader().decode(BinaryBitmap(attemptFactory()), hints).text
                } catch (e: ReaderException) {
                    // try next attempt
                }
            }
        }
        throw IllegalArgumentException("没有识别到二维码")
    }

    /** Returns a half-size copy of [bitmap]; cheap and helps ZXing when modules are noisy. */
    private fun downscale(bitmap: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)

    private fun decodeWith(reader: MultiFormatReader, binarizer: com.google.zxing.Binarizer): String {
        val bb = BinaryBitmap(binarizer)
        val result: Result = reader.decodeWithState(bb)
        return result.text
    }

    private fun bitmapToLuminanceSource(bitmap: Bitmap): RGBLuminanceSource {
        // Some Android decoders (BitmapFactory on recent OEM/Pixel builds) hand back
        // Bitmap.Config.HARDWARE, which makes getPixels() return all-zero. Copy to
        // ARGB_8888 first to be safe — ZXing needs CPU-readable pixels.
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
        val w = readable.width
        val h = readable.height
        val pixels = IntArray(w * h)
        readable.getPixels(pixels, 0, w, 0, 0, w, h)
        return RGBLuminanceSource(w, h, pixels)
    }

    /**
     * Reads one QR code per bitmap and assembles a single WTY3 payload, handling both
     * single-QR ("WTY3:...") and multi-QR ("WTYP1|...") inputs. Throws on inconsistent
     * or missing chunks.
     */
    fun assembleEncryptedPayload(bitmaps: List<Bitmap>): String {
        require(bitmaps.isNotEmpty()) { "未选择图片" }
        val scans = bitmaps.map { readQrText(it) }
        return assemblePayloadFromTexts(scans)
    }

    fun assemblePayloadFromTexts(texts: List<String>): String {
        require(texts.isNotEmpty()) { "未识别到二维码" }
        // Single-payload case: any bitmap already carries the full WTY3.
        for (t in texts) {
            if (SecurePayloadCodec.isPayload(t)) return t
        }
        // Multi-chunk case: all bitmaps must use the WTYP1 wrapper.
        if (texts.all { it.startsWith("$MULTI_PREFIX$DELIM") }) {
            return assembleMultiQrChunks(texts)
        }
        throw IllegalArgumentException("二维码不是文图易加密格式")
    }

    private fun assembleMultiQrChunks(texts: List<String>): String {
        var id: String? = null
        var total = -1
        val parts = HashMap<Int, String>()
        for (t in texts) {
            val fields = t.split(DELIM, limit = 5)
            require(fields.size == 5 && fields[0] == MULTI_PREFIX) { "二维码不是文图易加密格式" }
            val chunkId = fields[1]
            val n = fields[2].toIntOrNull() ?: throw IllegalArgumentException("二维码页码异常")
            val t2 = fields[3].toIntOrNull() ?: throw IllegalArgumentException("二维码总页数异常")
            val chunk = fields[4]
            require(t2 in 1..MAX_QR_PAGES && n in 1..t2) {
                // Hard cap on total — a malicious sender could otherwise advertise
                // total=Int.MAX_VALUE and force the receiver into a multi-GB
                // (1..total).filter { ... } walk + StringBuilder allocation when
                // computing the "missing pages" error message.
                "二维码页码异常或总页数超出上限 ($MAX_QR_PAGES)"
            }
            if (id == null) {
                id = chunkId
                total = t2
            } else {
                require(id == chunkId) { "扫到的二维码不属于同一条消息" }
                require(total == t2) { "二维码总页数不一致" }
            }
            parts[n] = chunk
        }
        if (parts.size != total) {
            val missing = (1..total).filter { it !in parts.keys }
            throw IllegalArgumentException(
                "二维码不完整：已识别 ${parts.size}/$total，缺第 ${missing.joinToString("、")} 页"
            )
        }
        val sb = StringBuilder()
        for (i in 1..total) {
            sb.append(parts[i] ?: throw IllegalArgumentException("缺少第 $i 页"))
        }
        val joined = sb.toString()
        // Verify the rebuilt payload hashes back to the chunk-id every chunk carries.
        // Catches cross-message splicing of chunks that share an id (would only happen
        // with v0.4 random ids; v0.5+ ids are payload-derived so this is also a sanity
        // check against tampering / mid-flight reordering).
        val expectedId = chunkIdFor(joined)
        if (id != null && expectedId != id) {
            throw IllegalArgumentException(
                "二维码内容校验失败：分片可能来自不同消息，或第 N 张被篡改"
            )
        }
        if (!SecurePayloadCodec.isPayload(joined)) {
            throw IllegalArgumentException("二维码内容格式不支持")
        }
        return joined
    }

    // ─── QR encoding ──────────────────────────────────────────────────────────

    private fun encodeQrBitmap(content: String, title: String, footer: String, meta: String,
                               paddingShift: Int = 0): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // Quiet zone: spec minimum is 4. Pushing higher (we tried 8) actually
            // hurts because ZXing rounds the per-module px down to fit dim, and the
            // extra quiet zone eats into the budget. v0.5.2 retests confirmed 4 is
            // the sweet spot when paired with the 3-binarizer fallback in readQrText.
            EncodeHintType.MARGIN to 4,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE,
            QR_MATRIX_PIXEL, QR_MATRIX_PIXEL, hints)

        val mw = matrix.width
        val mh = matrix.height
        // Trimmed from v0.5.1's (200/140/60) — wide white padding around the QR
        // confused ZXing's finder-pattern detector at low JPEG quality. The
        // paddingShift parameter is used by [encodeQrBitmapValidated] to retry
        // with slightly different geometry when the self-decode-check fails.
        val padTop = 120 + paddingShift * 5
        val padBottom = 80 + paddingShift * 5
        val padHorizontal = 40 + paddingShift * 5
        val width = mw + padHorizontal * 2
        val height = padTop + mh + padBottom

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 87, 63)
            isFakeBoldText = true
            textSize = 48f
        }
        canvas.drawText(title, padHorizontal.toFloat(), 80f, titlePaint)

        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 102, 90)
            textSize = 26f
        }
        canvas.drawText(meta, padHorizontal.toFloat(), 120f, metaPaint)

        val qrBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 224, 214)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val left = padHorizontal.toFloat()
        val top = padTop.toFloat()
        val right = left + mw
        val bottom = top + mh
        canvas.drawRect(left - 10, top - 10, right + 10, bottom + 10, qrBg)
        canvas.drawRoundRect(RectF(left - 10, top - 10, right + 10, bottom + 10), 12f, 12f, border)

        // setPixel directly into the bitmap instead of canvas.drawRect(1px) — the
        // float-coordinate drawRect path triggered subpixel rendering on some GPUs,
        // bleeding the QR module edges and pushing ZXing into the ~20% flakiness
        // we saw in v0.5.2 multi-QR tests. setPixel is pure CPU and guaranteed
        // pixel-exact.
        val pixels = IntArray(mw)
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                pixels[x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            bitmap.setPixels(pixels, 0, mw, left.toInt(), (top + y).toInt(), mw, 1)
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 102, 90)
            textSize = 24f
        }
        canvas.drawText(footer, padHorizontal.toFloat(), height - 50f, footerPaint)
        return bitmap
    }

    private fun payloadLabel(payload: String): String = when {
        payload.startsWith(SecurePayloadCodec.PREFIX_V3) -> "WTY3"
        payload.startsWith(SecurePayloadCodec.PREFIX_V2) -> "WTY2"
        else -> "WTY"
    }

    // ─── Plain styled text image (kept for the "图" toolbar action) ──────────

    private fun renderStyledTextImage(
        text: String, title: String, width: Int, padding: Int,
        textSize: Float, titleSize: Float, minHeight: Int,
        titleBaseline: Float, ruleY: Float, textTop: Float,
        lineExtra: Int, bottomPadding: Int,
    ): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(21, 24, 18)
            this.textSize = textSize
        }
        val contentWidth = width - padding * 2
        val lines = wrapText(text, textPaint, contentWidth)
        val metrics = textPaint.fontMetrics
        val lineHeight = Math.round(metrics.descent - metrics.ascent + lineExtra)
        val height = maxOf(
            minHeight,
            Math.round(textTop - metrics.ascent + lines.size * lineHeight + bottomPadding)
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 251, 247))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 122, 89)
            isFakeBoldText = true
            this.textSize = titleSize
        }
        canvas.drawText(title, padding.toFloat(), titleBaseline, titlePaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(222, 227, 217)
            strokeWidth = 3f
        }
        canvas.drawLine(padding.toFloat(), ruleY, (width - padding).toFloat(), ruleY, rulePaint)

        var y = textTop - metrics.ascent
        for (line in lines) {
            canvas.drawText(line, padding.toFloat(), y, textPaint)
            y += lineHeight
        }
        return bitmap
    }

    /**
     * Draws [text] with background noise dots, per-line micro-rotation and per-glyph
     * vertical jitter + variable advance, so glyph edges/baselines won't line up for an
     * OCR segmentation pass — while staying legible to a human. [noiseLevel] (0–100)
     * scales dot count and jitter amplitude.
     */
    private fun renderAntiOcr(text: String, noiseLevel: Int): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(33, 33, 33)
            textSize = 44f
            isFakeBoldText = true
        }
        val contentWidth = ANTIOCR_WIDTH - ANTIOCR_PADDING * 2
        val lines = wrapText(text, textPaint, contentWidth)
        val metrics = textPaint.fontMetrics
        val lineHeight = Math.round(metrics.descent - metrics.ascent + 22)
        // Cap height like the encrypt path caps inputs — a hostile/huge paste can't blow up memory.
        val rawHeight = Math.round(ANTIOCR_PADDING * 2 - metrics.ascent + lines.size * lineHeight)
        val height = rawHeight.coerceIn(180, 8192)
        val bitmap = Bitmap.createBitmap(ANTIOCR_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(247, 247, 247))

        if (noiseLevel > 0) {
            val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(noiseLevel * 50) {
                noisePaint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                noisePaint.alpha = random.nextInt(50) + 30
                canvas.drawCircle(
                    random.nextInt(ANTIOCR_WIDTH).toFloat(),
                    random.nextInt(height).toFloat(),
                    random.nextInt(3).toFloat(),
                    noisePaint
                )
            }
        }

        var y = ANTIOCR_PADDING * 2 - metrics.ascent
        for (line in lines) {
            var x = ANTIOCR_PADDING.toFloat()
            canvas.save()
            canvas.rotate((random.nextFloat() - 0.5f) * (noiseLevel / 100f) * 3f, x, y)
            for (ch in line) {
                val s = ch.toString()
                val dy = (random.nextFloat() - 0.5f) * (noiseLevel / 100f) * 6f
                canvas.drawText(s, x, y + dy, textPaint)
                x += textPaint.measureText(s) + random.nextFloat() * 4f
            }
            canvas.restore()
            y += lineHeight
        }
        return bitmap
    }

    private fun wrapText(text: String?, paint: Paint, maxWidth: Int): List<String> {
        val lines = ArrayList<String>()
        val source = text ?: ""
        for (paragraph in source.split("\n")) {
            if (paragraph.isEmpty()) {
                lines += ""
                continue
            }
            var start = 0
            while (start < paragraph.length) {
                var count = paint.breakText(paragraph, start, paragraph.length, true,
                    maxWidth.toFloat(), null)
                if (count <= 0) count = 1
                lines += paragraph.substring(start, start + count)
                start += count
            }
        }
        if (lines.isEmpty()) lines += ""
        return lines
    }
}
