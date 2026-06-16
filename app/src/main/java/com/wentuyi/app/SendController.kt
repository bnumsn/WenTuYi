package com.wentuyi.app

import com.wentuyi.protocol.CryptoUtils

import com.wentuyi.protocol.SecurePayloadCodec

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the 文图易 send actions plus the delivery + safety chain for each.
 *
 * Delivery is **direct only — no clipboard**: encrypted text is committed into the
 * focused box; images are inline-committed via commitContent when the app accepts
 * image content, else handed to the share sheet. Two cross-cutting concerns:
 *
 *   • **Target anchor** — Argon2id encryption takes ~150 ms; in that window the
 *     user can swipe to another app or focus another field. We capture
 *     `(packageName, sessionId)` before launching the coroutine and refuse to
 *     `commitText`/`commitContent` if the focus moved (images then go to share).
 *
 *   • **Session keys vs shared passphrase** — the IME resolves a [SendTarget]
 *     for each tap; "share with contact Alice" gives us a 32-byte X25519 session
 *     key, "shared key" falls back to the legacy passphrase path. v3 envelopes
 *     embed the key-mode byte so receivers route to the right decrypt path.
 */
class SendController(
    private val service: InputMethodService,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val resolveSendTarget: () -> SendTarget,
    /**
     * IME-supplied monotonic session id that increments on every fresh onStartInput
     * (`restarting == false`). We can't trust [EditorInfo.fieldId] for drift detection
     * because WeChat / Telegram / many WebView-backed chats pass 0 for every field.
     */
    private val currentSessionId: () -> Long,
) {

    private companion object {
        const val MAX_FIELD_CHARS = 100_000
    }

    private enum class TextReplaceResult {
        COMMITTED,
        SOURCE_CHANGED,
        FAILED,
    }

    sealed class SendTarget {
        object SharedPassphrase : SendTarget()
        data class Contact(
            val contact: KeyExchange.Contact,
            val identity: KeyExchange.Identity,
        ) : SendTarget()

        /**
         * Target resolution failed (selected contact vanished, identity key unreadable,
         * …). The send is refused with [reason] — we must **never** silently fall back to
         * the shared passphrase, or a message the user thinks is going to a verified
         * contact could become readable by everyone who knows the old shared key.
         */
        data class Unavailable(val reason: String) : SendTarget()
    }

    private data class TargetAnchor(val packageName: String?, val sessionId: Long, val fieldId: Int)

    fun commitPlainText(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val connection = service.currentInputConnection
        if (connection == null) { onStatus("当前输入框不可写"); return }
        connection.commitText(text, 1)
        onStatus("已写入文字")
    }

    fun generatePlainTextImage(text: String) = generateTextImage(text, antiOcr = false)

    /** Plaintext PNG that's human-readable but noisy/jittered to defeat machine OCR. */
    fun generateAntiOcrImage(text: String) = generateTextImage(text, antiOcr = true)

    private fun generateTextImage(text: String, antiOcr: Boolean) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        onStatus(if (antiOcr) "正在生成防 OCR 图片..." else "正在生成图片...")
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    if (antiOcr) TextImageCodec.renderAntiOcrTextImage(text)
                    else TextImageCodec.renderPlainTextImage(text)
                }
                val uri = withContext(Dispatchers.IO) { ImageStore.savePng(service, bitmap) }
                deliverImages(
                    listOf(uri), anchor,
                    if (antiOcr) "已插入防 OCR 图片" else "已插入文字图片",
                    if (antiOcr) "已分享防 OCR 图片" else "已分享文字图片",
                    text,
                )
            } catch (e: Exception) {
                onStatus("生成失败：${e.userMessage()}")
            }
        }
    }

    fun commitEncryptedText(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        val target = resolveSendTarget()
        rejectTarget(target)?.let { onStatus(it); return }
        onStatus(progressLabel("正在加密文字...", target))
        scope.launch {
            try {
                val enc = withContext(Dispatchers.Default) { encryptText(text, target) }
                deliverEncryptedText(enc.payload, anchor, target, text, enc.noForwardSecrecy)
            } catch (e: Exception) {
                onStatus("加密失败：${e.userMessage()}")
            }
        }
    }

    fun generateEncryptedImage(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        val target = resolveSendTarget()
        rejectTarget(target)?.let { onStatus(it); return }
        onStatus(progressLabel("正在生成加密二维码...", target))
        scope.launch {
            try {
                val qr = withContext(Dispatchers.Default) { encryptToQrBitmaps(text, target) }
                val uris = withContext(Dispatchers.IO) { qr.bitmaps.map { ImageStore.savePng(service, it) } }
                val pfsNote = if (qr.noForwardSecrecy) "（暂无前向保密）" else ""
                val suffix = " (${targetLabel(target)})$pfsNote"
                deliverImages(
                    uris,
                    anchor,
                    if (uris.size == 1) "已插入加密二维码$suffix" else "已插入 ${uris.size} 张加密二维码$suffix",
                    if (uris.size == 1) "已分享加密二维码$suffix" else "已分享 ${uris.size} 张加密二维码$suffix",
                    text,
                )
            } catch (e: Exception) {
                onStatus("加密失败：${e.userMessage()}")
            }
        }
    }

    // ─── Crypto routing ──────────────────────────────────────────────────────

    /** Encrypted text + whether it had to fall back off the forward-secret ratchet path. */
    private class EncryptedText(val payload: String, val noForwardSecrecy: Boolean)

    private fun encryptText(text: String, target: SendTarget): EncryptedText = when (target) {
        is SendTarget.SharedPassphrase ->
            EncryptedText(
                SecurePayloadCodec.encryptTextToPayload(text, WentuyiSettings.getPassphrase(service)),
                noForwardSecrecy = false,  // shared-key mode is a deliberate choice, not a downgrade
            )
        is SendTarget.Contact -> {
            // Prefer the Double Ratchet (WTY5, forward-secret). Falls back to the WTY4
            // session key only when the ratchet has no sending chain yet (responder before
            // it has received the initiator's first message) — surfaced to the user below.
            val ratchet = RatchetSession.encryptText(service, target.identity, target.contact, text)
            if (ratchet != null) {
                EncryptedText(ratchet, noForwardSecrecy = false)
            } else {
                val secret = KeyExchange.deriveSharedSecret(target.identity, target.contact.publicKey)
                try {
                    EncryptedText(SecurePayloadCodec.encryptTextWithSessionKey(text, secret),
                        noForwardSecrecy = true)
                } finally {
                    CryptoUtils.wipe(secret)
                }
            }
        }
        // Defensive: callers reject Unavailable before encrypting; never downgrade here.
        is SendTarget.Unavailable -> throw IllegalStateException(target.reason)
    }

    /** QR bitmaps + whether it fell back off the forward-secret ratchet path. */
    private class EncryptedQr(val bitmaps: List<android.graphics.Bitmap>, val noForwardSecrecy: Boolean)

    private fun encryptToQrBitmaps(text: String, target: SendTarget): EncryptedQr = when (target) {
        is SendTarget.SharedPassphrase ->
            EncryptedQr(
                TextImageCodec.renderEncryptedTextAsQr(text, WentuyiSettings.getPassphrase(service)),
                noForwardSecrecy = false,
            )
        is SendTarget.Contact -> {
            // Same ratchet-first / WTY4-fallback rule as the text path. The ratchet message
            // key is consumed + persisted here; a never-scanned QR just becomes a skipped
            // message on the receiver, which the ratchet tolerates.
            val ratchet = RatchetSession.encryptText(service, target.identity, target.contact, text)
            if (ratchet != null) {
                EncryptedQr(TextImageCodec.renderEncryptedPayloadAsQr(ratchet), noForwardSecrecy = false)
            } else {
                val secret = KeyExchange.deriveSharedSecret(target.identity, target.contact.publicKey)
                try {
                    EncryptedQr(TextImageCodec.renderEncryptedTextAsQr(text, secret), noForwardSecrecy = true)
                } finally {
                    CryptoUtils.wipe(secret)
                }
            }
        }
        is SendTarget.Unavailable -> throw IllegalStateException(target.reason)
    }

    // ─── Delivery primitives ─────────────────────────────────────────────────

    private fun deliverEncryptedText(
        payload: String,
        anchor: TargetAnchor,
        target: SendTarget,
        sourceText: String,
        noForwardSecrecy: Boolean,
    ) {
        val targetLabel = targetLabel(target)
        if (!anchorStillCurrent(anchor)) { onStatus("目标已切换，未写入"); return }
        val connection = service.currentInputConnection ?: run { onStatus("当前输入框不可写"); return }
        // Tell the user when a contact send couldn't use the forward-secret ratchet yet
        // (responder before first receive) and silently fell back to the WTY4 session key.
        val pfsNote = if (noForwardSecrecy) "（本条暂无前向保密，待对方回复后自动启用）" else ""
        when (replaceVisibleTextIfMatches(connection, sourceText, payload)) {
            TextReplaceResult.COMMITTED -> onStatus("已写入加密文字 ($targetLabel)$pfsNote")
            TextReplaceResult.SOURCE_CHANGED -> onStatus("输入内容已变化，未写入")
            TextReplaceResult.FAILED -> onStatus("写入失败")
        }
    }

    private fun replaceVisibleTextIfMatches(
        connection: InputConnection,
        sourceText: String,
        replacement: String,
    ): TextReplaceResult =
        runCatching {
            connection.beginBatchEdit()
            try {
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    if (selected.toString() != sourceText) return@runCatching TextReplaceResult.SOURCE_CHANGED
                    if (connection.commitText(replacement, 1)) TextReplaceResult.COMMITTED
                    else TextReplaceResult.FAILED
                } else {
                    val before = connection.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
                    val after = connection.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
                    if (before + after != sourceText) return@runCatching TextReplaceResult.SOURCE_CHANGED
                    if (before.isNotEmpty() || after.isNotEmpty()) {
                        if (!connection.deleteSurroundingText(before.length, after.length)) {
                            return@runCatching TextReplaceResult.FAILED
                        }
                    }
                    if (connection.commitText(replacement, 1)) TextReplaceResult.COMMITTED
                    else TextReplaceResult.FAILED
                }
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(TextReplaceResult.FAILED)

    /**
     * Direct send only — no clipboard. Inline-commit the image(s) into the focused
     * chat box if the app accepts image content; otherwise open the share sheet so
     * the user can pick the app. Those are the only two ways an IME can hand an image
     * to another app without using the clipboard.
     */
    private fun deliverImages(
        uris: List<Uri>,
        anchor: TargetAnchor,
        commitOk: String,
        sharedOk: String,
        sourceText: String? = null,
    ) {
        if (uris.isEmpty()) { onStatus("没有可发送的图片"); return }

        if (anchorStillCurrent(anchor) && canCommitImageToTarget()) {
            val connection = service.currentInputConnection
            val clearedSource = connection != null &&
                sourceText != null &&
                clearVisibleTextIfMatches(connection, sourceText)
            val (committedAll, committedCount) = tryCommitImages(uris)
            if (committedAll) { onStatus(commitOk); return }
            if (clearedSource && committedCount == 0 && sourceText != null) {
                restoreVisibleText(connection, sourceText)
            }
            val remaining = if (committedCount > 0) uris.drop(committedCount) else uris
            val shared = shareImages(remaining, anchor.packageName)
            if (shared) {
                onStatus(if (committedCount > 0) "$commitOk（$committedCount 张已插入，其余转分享）" else sharedOk)
            }
            return
        }

        // Couldn't inline-insert. Clear the original plaintext first — it's being
        // replaced by an out-of-band encrypted image, so leaving it in the box risks the
        // user sending the cleartext by accident — then share. Restore it if the share
        // never launches. Only touch the field if the anchor is still the live target.
        val connection = service.currentInputConnection
        val clearedSource = connection != null && sourceText != null &&
            anchorStillCurrent(anchor) && clearVisibleTextIfMatches(connection, sourceText)
        val preferred = anchor.packageName.takeIf { anchorStillCurrent(anchor) }
        val shared = shareImages(uris, preferred)
        if (shared) {
            onStatus(sharedOk)
        } else if (clearedSource && sourceText != null) {
            restoreVisibleText(connection, sourceText)
        }
        // else: startChooser already set "没有可用的分享应用".
    }

    private fun clearVisibleTextIfMatches(connection: InputConnection, sourceText: String): Boolean =
        runCatching {
            connection.beginBatchEdit()
            try {
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    return@runCatching selected.toString() == sourceText && connection.commitText("", 1)
                }
                val before = connection.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
                val after = connection.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
                if (before + after != sourceText) return@runCatching false
                if (before.isEmpty() && after.isEmpty()) return@runCatching true
                connection.deleteSurroundingText(before.length, after.length)
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(false)

    private fun restoreVisibleText(connection: InputConnection?, sourceText: String) {
        runCatching { connection?.commitText(sourceText, 1) }
    }

    private fun tryCommitImages(uris: List<Uri>): Pair<Boolean, Int> {
        if (!canCommitImageToTarget()) return false to 0
        var committed = 0
        for (uri in uris) {
            if (!commitOneImage(uri)) break
            committed++
        }
        return (committed == uris.size) to committed
    }

    private fun canCommitImageToTarget(): Boolean {
        if (Build.VERSION.SDK_INT < 25) return false
        val ei = service.currentInputEditorInfo ?: return false
        val types = ei.contentMimeTypes ?: return false
        return types.any { it.equals("image/png", ignoreCase = true) || it.equals("image/*", ignoreCase = true) }
    }

    private fun commitOneImage(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < 25) return false
        val connection = service.currentInputConnection ?: return false
        val ei = service.currentInputEditorInfo ?: return false
        val types = ei.contentMimeTypes ?: return false
        if (!types.any { it.equals("image/png", true) || it.equals("image/*", true) }) return false
        return try {
            ei.packageName?.let {
                service.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val description = ClipDescription("文图易图片", arrayOf("image/png"))
            val info = InputContentInfo(uri, description, null)
            connection.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, Bundle())
        } catch (e: Exception) {
            false
        }
    }

    // ─── Target anchor (drift defense) ───────────────────────────────────────

    private fun captureAnchor(): TargetAnchor? {
        val ei = service.currentInputEditorInfo ?: return null
        // fieldId is unreliable in WeChat/Telegram (always 0) so it can't be the sole
        // signal, but when an app *does* populate it, requiring it to match tightens
        // drift detection within a reused input session — it never loosens it.
        return TargetAnchor(ei.packageName, currentSessionId(), ei.fieldId)
    }

    private fun anchorStillCurrent(anchor: TargetAnchor): Boolean {
        val current = captureAnchor() ?: return false
        return current.packageName == anchor.packageName &&
            current.sessionId == anchor.sessionId &&
            current.fieldId == anchor.fieldId
    }

    // ─── Share-sheet fallback ────────────────────────────────────────────────

    private fun shareImages(uris: List<Uri>, preferredPackage: String?): Boolean {
        if (uris.isEmpty()) return false
        val share = buildShareIntent(uris)
        // No blanket pre-grant to every app that *could* handle the intent — that left a
        // read grant on the cached PNG for far more packages than ever receive it. The
        // intent's FLAG_GRANT_READ_URI_PERMISSION (+ clipData) grants transiently to only
        // the component that actually receives it; directShareTo grants to its one pkg.
        // Drop straight into the app the user is composing in (WeChat/QQ) so it opens
        // that app's "send to contact" screen — no chooser step. Chooser is the fallback.
        if (preferredPackage != null && directShareTo(share, preferredPackage, uris)) return true
        return startChooser(share)
    }

    private fun buildShareIntent(uris: List<Uri>): Intent =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                clipData = ClipData.newUri(service.contentResolver, "文图易图片", uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                val clip = ClipData.newUri(service.contentResolver, "文图易图片", uris[0])
                for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    /** Launch the share intent directly into [pkg] (the foreground chat app). */
    private fun directShareTo(base: Intent, pkg: String, uris: List<Uri>): Boolean {
        val direct = Intent(base).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = service.packageManager.resolveActivity(direct, PackageManager.MATCH_DEFAULT_ONLY) != null
        if (!resolved) return false
        for (u in uris) service.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            service.startActivity(direct); true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns true on successful chooser launch; sets the status itself on failure. */
    private fun startChooser(share: Intent): Boolean {
        val chooser = Intent.createChooser(share, "发送文图易图片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            service.startActivity(chooser); true
        } catch (e: ActivityNotFoundException) {
            onStatus("没有可用的分享应用")
            false
        }
    }

    private fun targetLabel(target: SendTarget): String = when (target) {
        is SendTarget.SharedPassphrase -> "共享密钥"
        is SendTarget.Contact ->
            if (target.contact.verified) target.contact.name else "${target.contact.name}（未验证）"
        is SendTarget.Unavailable -> "不可用"
    }

    /** Returns a refusal message if [target] must not be sent to, else null. */
    private fun rejectTarget(target: SendTarget): String? = when {
        target is SendTarget.Unavailable -> target.reason
        target is SendTarget.Contact && !target.contact.verified ->
            "联系人 ${target.contact.name} 未验证；请先到主 App 核对 SAS 后再发送"
        else -> null
    }

    // Reachable only after rejectTarget() has cleared the target, so Contact is always
    // verified and Unavailable never gets here.
    private fun progressLabel(base: String, target: SendTarget): String = when (target) {
        is SendTarget.Contact -> "$base (${target.contact.name})"
        is SendTarget.SharedPassphrase -> "$base (共享密钥)"
        is SendTarget.Unavailable -> base
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
