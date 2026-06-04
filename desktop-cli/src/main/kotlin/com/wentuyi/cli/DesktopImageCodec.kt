package com.wentuyi.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wentuyi.protocol.PayloadChunks
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

object DesktopImageCodec {
    private const val PLAIN_WIDTH = 1080
    private const val PLAIN_PADDING = 72
    private const val QR_SIZE = 2048

    fun writePlainTextImage(text: String, out: Path): Path {
        require(text.isNotBlank()) { "text is blank" }
        Files.createDirectories(out.toAbsolutePath().parent)
        val image = renderPlainTextImage(text)
        ImageIO.write(image, "png", out.toFile())
        return out
    }

    fun writePayloadQrImages(payload: String, outDir: Path, prefix: String = "wentuyi-qr"): List<Path> {
        require(payload.isNotBlank()) { "payload is blank" }
        Files.createDirectories(outDir)
        val chunks = PayloadChunks.chunkPayload(payload)
        return chunks.mapIndexed { index, chunk ->
            val suffix = if (chunks.size == 1) "" else "-${index + 1}-of-${chunks.size}"
            val out = outDir.resolve("$prefix$suffix.png")
            val title = if (chunks.size == 1) "Wentuyi encrypted text" else "Wentuyi encrypted text ${index + 1}/${chunks.size}"
            val image = renderQrImage(chunk, title, "Scan to decrypt with Wentuyi")
            ImageIO.write(image, "png", out.toFile())
            out
        }
    }

    private fun renderPlainTextImage(text: String): BufferedImage {
        val font = Font(Font.SANS_SERIF, Font.PLAIN, 44)
        val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 42)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        probeGraphics.font = font
        val metrics = probeGraphics.fontMetrics
        val lines = wrapText(text, metrics, PLAIN_WIDTH - PLAIN_PADDING * 2)
        probeGraphics.dispose()

        val lineHeight = metrics.height + 18
        val height = maxOf(520, 174 + lines.size * lineHeight + 56)
        val image = BufferedImage(PLAIN_WIDTH, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF7, 0xF9, 0xFC)
        g.fillRect(0, 0, PLAIN_WIDTH, height)
        g.color = Color.WHITE
        g.fillRoundRect(38, 38, PLAIN_WIDTH - 76, height - 76, 28, 28)
        g.color = Color(0xD8, 0xE0, 0xEA)
        g.stroke = BasicStroke(2f)
        g.drawRoundRect(38, 38, PLAIN_WIDTH - 76, height - 76, 28, 28)
        g.font = titleFont
        g.color = Color(0x17, 0x24, 0x33)
        g.drawString("Wentuyi", PLAIN_PADDING, 82)
        g.color = Color(0xD8, 0xE0, 0xEA)
        g.drawLine(PLAIN_PADDING, 116, PLAIN_WIDTH - PLAIN_PADDING, 116)
        g.font = font
        g.color = Color(0x1F, 0x29, 0x37)
        var y = 174
        for (line in lines) {
            g.drawString(line, PLAIN_PADDING, y)
            y += lineHeight
        }
        g.dispose()
        return image
    }

    private fun renderQrImage(content: String, title: String, footer: String): BufferedImage {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val panelHeight = QR_SIZE + 250
        val image = BufferedImage(QR_SIZE + 160, panelHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF7, 0xF9, 0xFC)
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.WHITE
        g.fillRoundRect(40, 40, image.width - 80, image.height - 80, 30, 30)
        g.color = Color(0x17, 0x24, 0x33)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 48)
        g.drawString(title, 80, 92)
        val qrTop = 126
        g.color = Color.WHITE
        g.fillRect(80, qrTop, QR_SIZE, QR_SIZE)
        for (y in 0 until QR_SIZE) {
            for (x in 0 until QR_SIZE) {
                image.setRGB(80 + x, qrTop + y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        g.color = Color(0x4B, 0x55, 0x63)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 34)
        g.drawString(footer, 80, qrTop + QR_SIZE + 70)
        g.dispose()
        return image
    }

    private fun wrapText(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.replace("\r\n", "\n").split('\n')) {
            if (paragraph.isEmpty()) {
                lines += ""
                continue
            }
            var current = StringBuilder()
            for (ch in paragraph) {
                val candidate = current.toString() + ch
                if (current.isNotEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                    lines += current.toString()
                    current = StringBuilder().append(ch)
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines.ifEmpty { listOf("") }
    }
}
