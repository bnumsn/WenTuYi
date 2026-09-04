package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

class ScreenDecryptService : Service() {

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 7421
        private const val CHANNEL_ID = "screen_decrypt"
        private const val CAPTURE_TIMEOUT_MS = 3500L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenDecryptService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var completed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            cleanupCapture(stopProjection = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, ActivityResultMissing) ?: ActivityResultMissing
        val data = intent?.projectionData()
        if (resultCode == ActivityResultMissing || data == null) {
            finishFailure("截图授权结果丢失")
            return START_NOT_STICKY
        }
        handler.postDelayed({ startCapture(resultCode, data) }, 250L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cleanupCapture(stopProjection = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        ensureNotificationChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("文图易正在解图")
            .setContentText("正在截取当前微信画面识别密图")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕解密",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "文图易用于在聊天界面内识别密图二维码"
        }
        manager.createNotificationChannel(channel)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (completed) return
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException("系统不支持屏幕截图解密")
            val projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("无法启动屏幕截图")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, handler)

            val size = displaySize()
            imageReader = ImageReader.newInstance(
                size.width, size.height, PixelFormat.RGBA_8888, 2
            ).apply {
                setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, handler)
            }
            virtualDisplay = projection.createVirtualDisplay(
                "wentuyi-screen-decrypt",
                size.width,
                size.height,
                size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler,
            )
            handler.postDelayed({ finishFailure("截图超时，请重试") }, CAPTURE_TIMEOUT_MS)
        } catch (e: Exception) {
            finishFailure("截图失败：${e.userMessage()}")
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (completed) return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            ?: return
        completed = true
        handler.removeCallbacksAndMessages(null)
        val bitmap = try {
            image.toBitmap()
        } finally {
            image.close()
            cleanupCapture(stopProjection = true)
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { decryptScreenshot(bitmap) }
                finishSuccess(result)
            } catch (e: Exception) {
                finishFailure("解图失败：${e.userMessage()}")
            }
        }
    }

    private fun decryptScreenshot(bitmap: Bitmap): ScreenDecryptResult {
        val scans = TextImageCodec.readQrScans(bitmap)
        val payload = selectVisibleWentuyiPayload(scans)
        return when (val result = MessageDecryptor.decrypt(this, payload)) {
            is MessageDecryptor.Result.Success -> {
                if (result.payload.isText()) {
                    ScreenDecryptResult.Text(result.payload.text())
                } else {
                    val decoded = BitmapUtils.decodeImageBytes(result.payload.data)
                    val uri = ImageStore.saveDecryptedPng(this, decoded)
                    ScreenDecryptResult.Image(uri)
                }
            }
            is MessageDecryptor.Result.Failure -> throw GeneralSecurityException(result.message)
        }
    }

    private fun selectVisibleWentuyiPayload(scans: List<TextImageCodec.QrScan>): String {
        val choices = ArrayList<PayloadChoice>()
        var sawIdentity = false
        val chunksById = LinkedHashMap<String, MutableList<VisibleChunk>>()

        for (scan in scans) {
            val text = scan.text.trim()
            when {
                SecurePayloadCodec.isPayload(text) -> {
                    choices += PayloadChoice(scan.centerY, payload = text, error = null)
                }
                text.startsWith("${KeyExchange.QR_PREFIX}|") -> {
                    sawIdentity = true
                }
                text.startsWith("${TextImageCodec.MULTI_PREFIX}|") -> {
                    val chunk = parseChunk(scan) ?: continue
                    chunksById.getOrPut(chunk.id) { ArrayList() } += chunk
                }
            }
        }

        for ((_, chunks) in chunksById) {
            val centerY = chunks.maxOf { it.centerY }
            val totals = chunks.map { it.total }.distinct()
            if (totals.size != 1) {
                choices += PayloadChoice(centerY, null, "二维码总页数不一致")
                continue
            }
            val total = totals[0]
            val pages = chunks.map { it.page }.toSet()
            choices += if (pages.size == total) {
                val texts = chunks.sortedBy { it.page }.map { it.text }
                PayloadChoice(centerY, TextImageCodec.assemblePayloadFromTexts(texts), null)
            } else {
                val missing = (1..total).filter { it !in pages }.joinToString("、")
                PayloadChoice(centerY, null, "二维码不完整：已识别 ${pages.size}/$total，缺第 $missing 页")
            }
        }

        val selected = choices.maxByOrNull { it.centerY }
        if (selected != null) {
            selected.error?.let { throw IllegalArgumentException(it) }
            return selected.payload ?: throw IllegalArgumentException("二维码不是文图易加密格式")
        }
        if (sawIdentity) throw IllegalArgumentException("这是身份码，不是密图")
        throw IllegalArgumentException("当前屏幕没有识别到文图易密图二维码")
    }

    private fun parseChunk(scan: TextImageCodec.QrScan): VisibleChunk? {
        val fields = scan.text.split("|", limit = 5)
        if (fields.size != 5 || fields[0] != TextImageCodec.MULTI_PREFIX) return null
        val page = fields[2].toIntOrNull() ?: return null
        val total = fields[3].toIntOrNull() ?: return null
        if (page !in 1..total || total !in 1..32) return null
        return VisibleChunk(fields[1], page, total, scan.text, scan.centerY)
    }

    private fun finishSuccess(result: ScreenDecryptResult) {
        val intent = Intent(ScreenDecryptActivity.ACTION_RESULT).setPackage(packageName)
            .putExtra(ScreenDecryptActivity.EXTRA_OK, true)
        when (result) {
            is ScreenDecryptResult.Text -> {
                intent.putExtra(ScreenDecryptActivity.EXTRA_KIND, ScreenDecryptActivity.KIND_TEXT)
                intent.putExtra(ScreenDecryptActivity.EXTRA_TEXT, result.text)
            }
            is ScreenDecryptResult.Image -> {
                intent.putExtra(ScreenDecryptActivity.EXTRA_KIND, ScreenDecryptActivity.KIND_IMAGE)
                intent.putExtra(ScreenDecryptActivity.EXTRA_IMAGE_URI, result.uri.toString())
                intent.putExtra(ScreenDecryptActivity.EXTRA_TEXT, "已解密一张图片")
            }
        }
        publishResult(intent)
        stopSelf()
    }

    private fun finishFailure(message: String) {
        if (!completed) completed = true
        cleanupCapture(stopProjection = true)
        publishResult(Intent(ScreenDecryptActivity.ACTION_RESULT).setPackage(packageName)
            .putExtra(ScreenDecryptActivity.EXTRA_OK, false)
            .putExtra(ScreenDecryptActivity.EXTRA_MESSAGE, message))
        stopSelf()
    }

    private fun publishResult(intent: Intent) {
        ScreenDecryptStore.save(this, intent)
        sendBroadcast(intent)
    }

    private fun cleanupCapture(stopProjection: Boolean) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        if (stopProjection) {
            mediaProjection?.let {
                runCatching { it.unregisterCallback(projectionCallback) }
                runCatching { it.stop() }
            }
        }
        mediaProjection = null
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): CaptureSize {
        val density = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(WindowManager::class.java)
            val bounds = manager.currentWindowMetrics.bounds
            CaptureSize(bounds.width(), bounds.height(), density)
        } else {
            val metrics = DisplayMetrics()
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            manager.defaultDisplay.getRealMetrics(metrics)
            CaptureSize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: throw IllegalArgumentException("截图为空")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int)
    private data class PayloadChoice(val centerY: Float, val payload: String?, val error: String?)
    private data class VisibleChunk(
        val id: String,
        val page: Int,
        val total: Int,
        val text: String,
        val centerY: Float,
    )

    private sealed class ScreenDecryptResult {
        data class Text(val text: String) : ScreenDecryptResult()
        data class Image(val uri: Uri) : ScreenDecryptResult()
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}

private const val ActivityResultMissing = Int.MIN_VALUE
