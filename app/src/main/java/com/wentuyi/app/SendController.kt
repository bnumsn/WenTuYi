package com.wentuyi.app

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

    sealed class SendTarget {
        object SharedPassphrase : SendTarget()
        data class Contact(
            val contact: KeyExchange.Contact,
            val identity: KeyExchange.Identity,
        ) : SendTarget()
    }

    private data class TargetAnchor(val packageName: String?, val sessionId: Long)

    fun commitPlainText(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val connection = service.currentInputConnection
        if (connection == null) { onStatus("当前输入框不可写"); return }
        connection.commitText(text, 1)
        onStatus("已写入文字")
    }

    fun generatePlainTextImage(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        onStatus("正在生成图片...")
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    TextImageCodec.renderPlainTextImage(text)
                }
                val uri = withContext(Dispatchers.IO) { ImageStore.savePng(service, bitmap) }
                deliverImages(listOf(uri), anchor, "已插入文字图片", "已分享文字图片", text)
            } catch (e: Exception) {
                onStatus("生成失败：${e.userMessage()}")
            }
        }
    }

    fun commitEncryptedText(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        val target = resolveSendTarget()
        onStatus(progressLabel("正在加密文字...", target))
        scope.launch {
            try {
                val payload = withContext(Dispatchers.Default) { encryptText(text, target) }
                deliverEncryptedText(payload, anchor, target)
            } catch (e: Exception) {
                onStatus("加密失败：${e.userMessage()}")
            }
        }
    }

    fun generateEncryptedImage(text: String) {
        if (text.isEmpty()) { onStatus("没有文字"); return }
        val anchor = captureAnchor() ?: run { onStatus("当前输入框不可写"); return }
        val target = resolveSendTarget()
        onStatus(progressLabel("正在生成加密二维码...", target))
        scope.launch {
            try {
                val bitmaps = withContext(Dispatchers.Default) {
                    encryptToQrBitmaps(text, target)
                }
                val uris = withContext(Dispatchers.IO) { bitmaps.map { ImageStore.savePng(service, it) } }
                val suffix = " (${targetLabel(target)})"
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

    private fun encryptText(text: String, target: SendTarget): String = when (target) {
        is SendTarget.SharedPassphrase ->
            SecurePayloadCodec.encryptTextToPayload(text, WentuyiSettings.getPassphrase(service))
        is SendTarget.Contact -> {
            val secret = KeyExchange.deriveSharedSecret(target.identity, target.contact.publicKey)
            try {
                SecurePayloadCodec.encryptTextWithSessionKey(text, secret)
            } finally {
                CryptoUtils.wipe(secret)
            }
        }
    }

    private fun encryptToQrBitmaps(text: String, target: SendTarget) = when (target) {
        is SendTarget.SharedPassphrase ->
            TextImageCodec.renderEncryptedTextAsQr(text, WentuyiSettings.getPassphrase(service))
        is SendTarget.Contact -> {
            val secret = KeyExchange.deriveSharedSecret(target.identity, target.contact.publicKey)
            try {
                TextImageCodec.renderEncryptedTextAsQr(text, secret)
            } finally {
                CryptoUtils.wipe(secret)
            }
        }
    }

    // ─── Delivery primitives ─────────────────────────────────────────────────

    private fun deliverEncryptedText(payload: String, anchor: TargetAnchor, target: SendTarget) {
        val targetLabel = targetLabel(target)
        if (!anchorStillCurrent(anchor)) { onStatus("目标已切换，未写入"); return }
        val connection = service.currentInputConnection ?: run { onStatus("当前输入框不可写"); return }
        val committed = replaceVisibleText(connection, payload)
        onStatus(if (committed) "已写入加密文字 ($targetLabel)" else "写入失败")
    }

    private fun replaceVisibleText(connection: InputConnection, replacement: String): Boolean =
        runCatching {
            connection.beginBatchEdit()
            try {
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    connection.commitText(replacement, 1)
                } else {
                    val before = connection.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
                    val after = connection.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.length ?: 0
                    if (before > 0 || after > 0) connection.deleteSurroundingText(before, after)
                    connection.commitText(replacement, 1)
                }
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(false)

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

        // Couldn't inline-insert: send straight to the app still in focus, else chooser.
        val preferred = anchor.packageName.takeIf { anchorStillCurrent(anchor) }
        val shared = shareImages(uris, preferred)
        if (shared) onStatus(sharedOk)
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
        return TargetAnchor(ei.packageName, currentSessionId())
    }

    private fun anchorStillCurrent(anchor: TargetAnchor): Boolean {
        val current = captureAnchor() ?: return false
        return current.packageName == anchor.packageName && current.sessionId == anchor.sessionId
    }

    // ─── Share-sheet fallback ────────────────────────────────────────────────

    private fun shareImages(uris: List<Uri>, preferredPackage: String?): Boolean {
        if (uris.isEmpty()) return false
        val share = buildShareIntent(uris)
        grantSharePermissions(share, uris)
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

    private fun grantSharePermissions(intent: Intent, uris: List<Uri>) {
        val pm = service.packageManager
        val targets = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (target in targets) {
            val pkg = target.activityInfo?.packageName ?: continue
            for (u in uris) {
                service.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun targetLabel(target: SendTarget): String = when (target) {
        is SendTarget.SharedPassphrase -> "共享密钥"
        is SendTarget.Contact ->
            if (target.contact.verified) target.contact.name else "${target.contact.name}（未验证）"
    }

    private fun progressLabel(base: String, target: SendTarget): String = when (target) {
        is SendTarget.Contact ->
            if (target.contact.verified) "$base (${target.contact.name})"
            else "$base 未验证联系人：${target.contact.name}"
        is SendTarget.SharedPassphrase -> "$base (共享密钥)"
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
