package com.wentuyi.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Real-time QR scanner built on the framework camera2 API + ZXing — no CameraX/AndroidX,
 * keeping the AOSP-only dependency profile. Deliberately narrow: it previews the back
 * camera, decodes the first QR it sees from the Y (luminance) plane, and returns the raw
 * text via [EXTRA_QR_TEXT]. All content routing (identity vs encrypted payload) stays in
 * [ScanActivity.routeScannedTexts], so this unverified-by-CI camera code can't affect how
 * a scanned key/message is handled.
 */
class CameraScanActivity : Activity() {

    companion object {
        const val EXTRA_QR_TEXT = "qr_text"
        private const val REQ_CAMERA_PERMISSION = 9100
    }

    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var analysisSize: Size = Size(1280, 720)

    @Volatile private var done = false
    private val zxing = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        statusView = TextView(this).apply {
            text = "将二维码对准取景框…"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(36, 28, 36, 28)
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        done = false
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
            return
        }
        startBackgroundThread()
        if (textureView.isAvailable) openCamera()
        else textureView.surfaceTextureListener = surfaceListener
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startBackgroundThread()
                if (textureView.isAvailable) openCamera()
                else textureView.surfaceTextureListener = surfaceListener
            } else {
                Toast.makeText(this, "需要相机权限才能实时扫码", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                fail("没有可用的相机"); return
            }
            val chars = manager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageReader::class.java)
                ?.let { sizes -> analysisSize = chooseAnalysisSize(sizes) }

            imageReader = ImageReader.newInstance(
                analysisSize.width, analysisSize.height, android.graphics.ImageFormat.YUV_420_888, 2
            ).apply { setOnImageAvailableListener(onFrame, bgHandler) }

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(cameraId, stateCallback, bgHandler)
        } catch (e: Exception) {
            fail("打开相机失败：${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            startPreview(device)
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
        override fun onError(device: CameraDevice, error: Int) {
            device.close(); cameraDevice = null; fail("相机错误：$error")
        }
    }

    private fun startPreview(device: CameraDevice) {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(analysisSize.width, analysisSize.height)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader!!.surface
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        runCatching { session.setRepeatingRequest(request.build(), null, bgHandler) }
                            .onFailure { fail("预览启动失败：${it.message}") }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) = fail("相机会话配置失败")
                }, bgHandler)
        } catch (e: Exception) {
            fail("预览启动失败：${e.message}")
        }
    }

    private val onFrame = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            if (done) return@OnImageAvailableListener
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            // Y plane: rowStride is the true data width; crop to the visible image rect.
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                zxing.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
            } catch (e: Exception) { null } finally { zxing.reset() }
            if (text != null && !done) {
                done = true
                runOnUiThread { returnResult(text) }
            }
        } finally {
            image.close()
        }
    }

    private fun returnResult(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, text))
        finish()
    }

    private fun fail(message: String) {
        if (done) return
        done = true
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun chooseAnalysisSize(sizes: Array<Size>): Size {
        // Prefer something near 1280x720 — big enough to read dense QR, small enough to
        // decode every frame in real time.
        val target = 1280 * 720
        return sizes.filter { it.width * it.height <= target * 2 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    private fun startBackgroundThread() {
        if (bgThread != null) return
        bgThread = HandlerThread("wentuyi-camera").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    private fun closeCamera() {
        runCatching { captureSession?.close() }; captureSession = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { imageReader?.close() }; imageReader = null
    }
}
