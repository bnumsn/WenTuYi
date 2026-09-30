package com.wentuyi.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.wentuyi.protocol.SecurePayloadCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * One screen's work and result, retained across configuration changes. The worker owns
 * only the application context; detaching a view does not cancel a receive after it has
 * durably consumed a WTY5 message key. Plaintext stays in memory, never in saved state.
 * Public methods and the observer run on the main thread.
 */
internal class DecryptOperation(
    context: Context,
    private val decryptMessage: (Context, String) -> MessageDecryptor.Result = MessageDecryptor::decrypt,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()
    private val pending = mutableSetOf<Request>()
    private var lastSuccess: Request? = null
    private var observer: ((State) -> Unit)? = null

    var state: State = State.Idle
        private set

    sealed class State {
        object Idle : State()
        data class Busy(val message: String) : State()
        data class Success(val result: DecryptionResult) : State()
        data class Failure(val message: String, val encryptInstead: String? = null) : State()
    }

    private sealed class Request {
        data class Text(val payload: String) : Request()
        data class Images(val uris: List<Uri>) : Request()
        data class QrTexts(val texts: List<String>) : Request()
    }

    fun attach(observer: (State) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun detach() { observer = null }

    fun close() {
        detach()
        scope.cancel()
    }

    fun restoredAfterProcessDeath() {
        publish(State.Failure("页面已恢复；解密结果仅保存在内存中，原消息不会自动重复解密"))
    }

    fun decryptText(payload: String) = submit(Request.Text(payload))

    fun decryptImages(uris: List<Uri>) {
        if (uris.isEmpty() || uris.size > TextImageCodec.MAX_QR_PAGES) {
            publish(State.Failure("一次请选择 1 至 ${TextImageCodec.MAX_QR_PAGES} 张二维码图片"))
            return
        }
        submit(Request.Images(uris.toList()))
    }

    fun decryptQrTexts(texts: List<String>) = submit(Request.QrTexts(texts.toList()))

    private fun submit(request: Request) {
        // Repeated clipboard taps/share intents must not consume the same live operation
        // twice. Keep only the current successful result, not a global plaintext cache.
        if (request == lastSuccess && state is State.Success) return
        if (!pending.add(request)) return
        scope.launch {
            try {
                queue.withLock {
                    publish(State.Busy(if (request is Request.Images) "正在识别二维码…" else "正在解密文字…"))
                    try {
                        val result = withContext(Dispatchers.Default) {
                            when (request) {
                                is Request.Text -> resultFromMessage(decryptMessage(context, request.payload))
                                is Request.Images -> decryptUris(request.uris)
                                is Request.QrTexts -> decryptScannedTexts(request.texts)
                            }
                        }
                        lastSuccess = request
                        publish(State.Success(result))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val source = (request as? Request.Text)?.payload
                        val plainText = source?.takeUnless {
                            SecurePayloadCodec.isPayload(it) || it.startsWith(DoubleRatchet.PREFIX_V5)
                        }
                        publish(State.Failure(e.message?.takeIf { it.isNotBlank() }
                            ?: e::class.java.simpleName, plainText))
                    }
                }
            } finally {
                pending.remove(request)
            }
        }
    }

    private fun publish(next: State) {
        state = next
        observer?.invoke(next)
    }

    private suspend fun decryptUris(uris: List<Uri>): DecryptionResult {
        val qrTexts = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val bitmap = BitmapUtils.decodeQrImportImage(context.contentResolver, uri)
                try { TextImageCodec.readQrText(bitmap) } finally { bitmap.recycle() }
            }
        }
        return decryptScannedTexts(qrTexts)
    }

    private fun decryptScannedTexts(qrTexts: List<String>): DecryptionResult {
        val identityText = qrTexts.firstOrNull { it.startsWith("${KeyExchange.QR_PREFIX}|") }
        if (identityText != null) {
            val (name, publicKey) = KeyExchange.decodeIdentityFromQr(identityText)
            val identity = KeyExchange.getOrCreateIdentity(context)
            val sas = KeyExchange.shortAuthString(identity, publicKey)
            val contact = KeyExchange.Contact(name, publicKey)
            KeyExchange.saveContact(context, contact)
            return DecryptionResult(
                "已添加联系人：$name\n指纹：${contact.fingerprint}\n\n安全码：$sas\n\n" +
                    "双方扫描身份码后，通过电话或当面对比完整安全码。\n" +
                    "核对一致后，到「身份与密钥」标记已验证，才能发送加密内容。",
                "联系人已保存 (未验证)", null, emptyList(),
            )
        }
        return resultFromMessage(decryptMessage(context, TextImageCodec.assemblePayloadFromTexts(qrTexts)))
    }

    private fun resultFromMessage(result: MessageDecryptor.Result): DecryptionResult = when (result) {
        is MessageDecryptor.Result.Failure -> throw GeneralSecurityException(result.message)
        is MessageDecryptor.Result.Success -> {
            val decrypted = result.payload
            val base = if (decrypted.isText()) {
                val text = decrypted.text()
                DecryptionResult(text, "文字解密完成", text, emptyList())
            } else {
                DecryptionResult("已解密一张图片", "图片解密完成", null,
                    listOf(BitmapUtils.decodeImageBytes(decrypted.data)))
            }
            result.sender?.let { base.copy(statusText = "${base.statusText} · 来自 ${it.name}") } ?: base
        }
    }
}

internal data class DecryptionResult(
    val resultText: String,
    val statusText: String,
    val lastPlainText: String?,
    val images: List<Bitmap>,
)
