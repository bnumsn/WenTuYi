package com.wentuyi.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identity + contacts + (legacy) shared-passphrase management.
 *
 * The X25519 identity QR is the recommended path: scan a peer's identity from
 * [ScanActivity] to derive a deterministic session key and verify out-of-band via
 * the complete 256-bit safety code. The "共享密钥" controls remain for users who haven't migrated.
 */
class KeyManagementActivity : Activity() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var statusView: TextView
    private lateinit var identityImage: ImageView
    private lateinit var identityFingerprint: TextView
    private lateinit var contactsContainer: LinearLayout
    private lateinit var passphraseInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.refresh(this)
        buildUi()
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ─── UI ─────────────────────────────────────────────────────────────────

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.surface)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        SystemBarPadding.apply(root, dp(22), dp(18), dp(22), dp(22))
        scroll.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(heading("我的身份码"), matchWrap())
        identityImage = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Palette.card)
        }
        root.addView(identityImage, matchWrapWithTop(12))

        identityFingerprint = subtle("")
        root.addView(identityFingerprint, matchWrapWithTop(8))

        statusView = subtle("")
        root.addView(statusView, matchWrapWithTop(4))

        val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(idRow, matchWrapWithTop(12))
        idRow.addView(button("导出身份码图片") { exportIdentityImage() }, weightWrap(1))
        idRow.addView(button("重新生成身份") { regenerateIdentity() }, weightWrapWithLeft(1, 10))

        val backupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(backupRow, matchWrapWithTop(10))
        backupRow.addView(button("备份身份") { showBackupDialog() }, weightWrap(1))
        backupRow.addView(button("从备份恢复") { showRestoreDialog() }, weightWrapWithLeft(1, 10))

        root.addView(heading("联系人 (扫描得到的对方身份码)"), matchWrapWithTop(28))
        contactsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contactsContainer, matchWrapWithTop(8))

        root.addView(heading("共享密钥 (兼容旧版)"), matchWrapWithTop(28))
        subtle("更推荐使用身份码 + 扫码加好友。共享密钥用 Argon2id 派生，泄露后即所有消息均失守。").also {
            root.addView(it, matchWrapWithTop(6))
        }
        passphraseInput = EditText(this).apply {
            isSingleLine = true
            setSelectAllOnFocus(true)
            hint = "输入新的共享密钥"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(passphraseInput, matchWrapWithTop(10))
        root.addView(button("保存共享密钥") { savePassphrase() }, matchWrapWithTop(8))

        setContentView(scroll)
    }

    // ─── Identity ───────────────────────────────────────────────────────────

    private fun refresh() {
        scope.launch {
            var identityError: String? = null
            val identity = try { KeyExchange.getOrCreateIdentity(this@KeyManagementActivity) }
                catch (e: Exception) { identityError = "身份码读取失败：${e.message}。请从备份恢复或重新生成。"; null }
            if (identity != null) {
                val bitmap = withContext(Dispatchers.Default) {
                    TextImageCodec.renderIdentityQr(identity, "文图易用户")
                }
                identityImage.setImageBitmap(bitmap)
                identityFingerprint.text = "我的指纹：${identity.fingerprint}"
            } else {
                identityImage.setImageDrawable(null)
                identityFingerprint.text = "身份不可读取；恢复入口仍可使用"
            }
            refreshContacts(identity)
            if (identityError != null) statusView.text = identityError else refreshPassphraseStatus()
        }
    }

    private fun refreshContacts(myIdentity: KeyExchange.Identity?) {
        contactsContainer.removeAllViews()
        // Migrate any v0.4 / v0.5 contacts saved before the low-order pubkey check.
        // Silent if nothing changes; surface the count when poisoned rows were dropped
        // so the user knows why a name vanished.
        val contacts = try {
            val pruned = if (myIdentity != null) KeyExchange.pruneInvalidContacts(this) else 0
            if (pruned > 0) statusView.text = "已清理 $pruned 个无效联系人（低阶/损坏公钥）"
            KeyExchange.listContacts(this)
        } catch (e: Exception) {
            // The contact blob is Keystore-wrapped and fails closed; show why rather than
            // crashing the screen the user came to in order to fix it.
            statusView.text = e.message ?: "联系人列表读取失败"
            contactsContainer.addView(subtle("联系人列表无法读取。可重建列表后重新扫码并核对校验码。"), matchWrap())
            contactsContainer.addView(button("重建联系人列表") {
                AlertDialog.Builder(this)
                    .setTitle("重建联系人列表")
                    .setMessage("这会清空全部联系人和加密会话，你的身份密钥保持不变。" +
                        "旧会话中尚未解密的消息可能无法恢复；请重新扫码添加联系人并核对校验码。")
                    .setPositiveButton("清空并重建") { _, _ ->
                        try {
                            KeyExchange.clearContactsAndSessions(this)
                            refresh()
                        } catch (failure: Exception) {
                            statusView.text = "重建失败：${failure.message}"
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }, matchWrap())
            return
        }
        if (contacts.isEmpty()) {
            contactsContainer.addView(subtle("（暂无）—— 从主页『扫码 / 导入二维码』添加对方身份码"), matchWrap())
            return
        }
        for (contact in contacts) {
            val sas = runCatching { myIdentity?.let { KeyExchange.shortAuthString(it, contact.publicKey) } ?: "—" }
                .getOrDefault("—")  // belt-and-braces: shouldn't fire after pruneInvalidContacts
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Palette.card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            nameRow.addView(TextView(this).apply {
                text = contact.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Palette.textPrimary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            nameRow.addView(TextView(this).apply {
                text = if (contact.verified) "✓ 已验证" else "未验证"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    if (contact.verified) Palette.accent
                    else Palette.warn
                )
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(nameRow, matchWrap())
            row.addView(subtle("指纹：${contact.fingerprint}"), matchWrapWithTop(2))
            row.addView(subtle(if (myIdentity == null) "请先恢复身份，再核对安全码" else
                "安全码：$sas\n请通过可信渠道逐组核对全部 16 组，不能只核对其中几组。").apply {
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }, matchWrapWithTop(2))
            if (!contact.verified) {
                row.addView(subtle("⚠ 未口外比对 SAS 前请勿用于敏感消息，可能存在中间人攻击").apply {
                    setTextColor(KeyboardUi.COLOR_DANGER)
                }, matchWrapWithTop(2))
            }

            val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actionsRow.addView(Button(this).apply {
                text = if (contact.verified) "取消已验证" else "标记已验证"
                isAllCaps = false
                isEnabled = myIdentity != null && sas != "—"
                setOnClickListener { showVerifyDialog(contact, sas) }
            }, weightWrap(1))
            actionsRow.addView(Button(this).apply {
                text = "重命名"
                isAllCaps = false
                setOnClickListener { showRenameDialog(contact) }
            }, weightWrapWithLeft(1, 10))
            actionsRow.addView(Button(this).apply {
                text = "移除"
                isAllCaps = false
                setOnClickListener { showRemoveDialog(contact) }
            }, weightWrapWithLeft(1, 10))
            row.addView(actionsRow, matchWrapWithTop(4))
            // Second row: the recovery action. Kept off the main row so the three everyday
            // buttons stay readable, and labelled plainly because this is what a user lands
            // on after "对方消息一直解不开".
            val recoveryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            recoveryRow.addView(Button(this).apply {
                text = "重置加密会话"
                isAllCaps = false
                setOnClickListener { showRestartRatchetDialog(contact) }
            }, weightWrap(1))
            row.addView(recoveryRow, matchWrapWithTop(4))
            contactsContainer.addView(row, matchWrapWithTop(10))
        }
    }

    /** Two-step verify dialog: shows the SAS one more time and requires explicit confirm. */
    private fun showVerifyDialog(contact: KeyExchange.Contact, sas: String) {
        if (contact.verified) {
            // One-tap unverify (rare; mostly for users who tapped by accident).
            AlertDialog.Builder(this)
                .setTitle("取消「${contact.name}」的已验证标记")
                .setMessage("如果你不再确定双方 SAS 仍一致，可以取消验证。下次发送加密消息时会再次警告。")
                .setPositiveButton("取消验证") { _, _ ->
                    KeyExchange.setContactVerified(this, contact.fingerprint, false)
                    refresh()
                }
                .setNegativeButton("保持已验证", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("确认「${contact.name}」的校验码")
                .setMessage(
                        "对方设备应该显示完全相同的完整安全码（16 组）：\n\n" +
                        "    $sas\n\n" +
                        "请通过电话、当面或其他可信渠道逐组核对全部字符（不要通过同一个聊天 App，也不要只核对首尾几组）。双方需使用支持完整安全码的版本。\n\n" +
                        "安全码不一致时，请勿标记已验证或发送敏感内容。旧版 8 位校验不再作为身份认证依据。"
                )
                .setPositiveButton("全部一致，标记已验证") { _, _ ->
                    try {
                        KeyExchange.setContactVerified(this, contact.fingerprint, true, expectedSafetyCode = sas)
                        refresh()
                    } catch (e: Exception) {
                        statusView.text = e.message ?: "验证失败，请重新核对安全码"
                    }
                }
                .setNegativeButton("再确认一下", null)
                .show()
        }
    }

    private fun exportIdentityImage() {
        scope.launch {
            try {
                val identity = KeyExchange.getOrCreateIdentity(this@KeyManagementActivity)
                val bitmap = withContext(Dispatchers.Default) {
                    TextImageCodec.renderIdentityQr(identity, "文图易用户")
                }
                val uri = withContext(Dispatchers.IO) {
                    ImageStore.savePng(this@KeyManagementActivity, bitmap)
                }
                IntentHelpers.shareImage(this@KeyManagementActivity, uri, "分享文图易身份码")
                statusView.text = "已打开分享"
            } catch (e: Exception) {
                statusView.text = "导出失败：${e.message}"
            }
        }
    }

    private fun regenerateIdentity() {
        AlertDialog.Builder(this)
            .setTitle("重新生成身份？")
            .setMessage("旧身份将立即作废：所有端到端加密会话清空、联系人重置为未验证（需重新核对完整安全码）。无法读取的联系人列表会被清空，之后需重新扫码。之前加你为联系人的对方需要重新扫码。此操作不可撤销。")
            .setPositiveButton("确认重置") { _, _ ->
                scope.launch {
                    try {
                        KeyExchange.replaceIdentity(this@KeyManagementActivity, discardUnreadableContacts = true)
                        statusView.text = "已重新生成身份。请重新交换给联系人。"
                        refresh()
                    } catch (e: Exception) {
                        statusView.text = "生成失败：${e.message}"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── Contact rename + delete confirmation ──────────────────────────────

    private fun showRenameDialog(contact: KeyExchange.Contact) {
        val input = EditText(this).apply {
            setText(contact.name)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名联系人")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim().take(40)
                if (newName.isEmpty()) {
                    statusView.text = "名字不能为空"; return@setPositiveButton
                }
                try {
                    KeyExchange.renameContact(this, contact.publicKey, newName)
                    refresh()
                } catch (e: Exception) {
                    statusView.text = e.message ?: "重命名失败"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Opens a fresh ratchet epoch with [contact]. This is the escape hatch for a session
     * that has gone out of sync — typically because this device reinstalled or had its data
     * cleared mid-conversation, leaving both sides holding root keys that no longer relate.
     * The peer adopts the new epoch automatically on our next message, so only one side has
     * to do this; the identity keypair and the verified flag are untouched.
     */
    private fun showRestartRatchetDialog(contact: KeyExchange.Contact) {
        AlertDialog.Builder(this)
            .setTitle("重置与「${contact.name}」的加密会话")
            .setMessage(
                "什么时候用：对方发来的加密消息一直提示无法解密（多半是任一方重装过、清过数据或用备份码换过机）。\n\n" +
                    "会发生什么：与 TA 的前向保密会话重新开始。此后你给 TA 发的第一条消息会自动让对方切到新会话，" +
                    "对方无需做任何操作。\n\n" +
                    "代价：还没解密的旧消息将永久无法解密。你的身份码、备份码和「已验证」标记都不受影响。"
            )
            .setPositiveButton("重置会话") { _, _ ->
                scope.launch {
                    statusView.text = try {
                        val identity = KeyExchange.loadIdentity(this@KeyManagementActivity)
                            ?: throw IllegalStateException("还没有身份码")
                        RatchetSession.restart(this@KeyManagementActivity, identity, contact)
                        "已重置与「${contact.name}」的加密会话，请给 TA 发一条消息完成恢复。"
                    } catch (e: Exception) {
                        "重置失败：${e.message}"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRemoveDialog(contact: KeyExchange.Contact) {
        AlertDialog.Builder(this)
            .setTitle("移除联系人")
            .setMessage("移除「${contact.name}」？以后你将无法解密 TA 之前发给你的会话加密消息。")
            .setPositiveButton("移除") { _, _ ->
                KeyExchange.removeContact(this, contact.fingerprint)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── Backup / restore ──────────────────────────────────────────────────

    private fun showBackupDialog() {
        scope.launch {
            try {
                val identity = KeyExchange.getOrCreateIdentity(this@KeyManagementActivity)
                val backup = KeyExchange.encodeBackup(identity)
                var visible = false
                val backupView = EditText(this@KeyManagementActivity).apply {
                    setText(formatBackupForDisplay(backup, masked = true))
                    setTextIsSelectable(true)
                    isFocusable = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    minLines = 5
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val toggleBtn = Button(this@KeyManagementActivity).apply {
                    text = "显示备份码"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setOnClickListener {
                        visible = !visible
                        backupView.setText(formatBackupForDisplay(backup, masked = !visible))
                        text = if (visible) "隐藏备份码" else "显示备份码"
                    }
                }
                val container = LinearLayout(this@KeyManagementActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                container.addView(backupView, matchWrap())
                container.addView(toggleBtn, matchWrap())
                val dialog = AlertDialog.Builder(this@KeyManagementActivity)
                    .setTitle("身份备份码")
                    .setMessage("把这段抄写或截图保存到密码管理器。任何人拿到都能冒充你 — 不要发到网上、不要存云盘。\n\n说明：发给已验证联系人的加密文本和二维码会优先使用前向保密 (Double Ratchet)；但共享密钥和收到对方首次回复前发出的全部棘轮消息暂无完整 PFS，私钥泄漏可能导致这部分历史消息被解密。")
                    .setView(container)
                    .setPositiveButton("复制到剪贴板") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("文图易身份备份码", backup))
                        statusView.text = "备份码已复制（注意：剪贴板可能被其他 App 读到，建议手抄；60 秒后自动清除）"
                        // The backup string is the private key in plaintext. Auto-clear it
                        // from the clipboard after 60s so it doesn't linger for clipboard
                        // sniffers. Only clear if it's still our value (don't clobber what
                        // the user copied since). App-process Handler, survives this dialog.
                        Handler(Looper.getMainLooper()).postDelayed({
                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@postDelayed
                            val current = cb.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(this@KeyManagementActivity)?.toString()
                            if (current == backup) {
                                cb.setPrimaryClip(ClipData.newPlainText("", ""))
                            }
                        }, 60_000)
                    }
                    .setNegativeButton("关闭", null)
                    .create()
                dialog.setOnDismissListener {
                    backupView.setText("")  // wipe visible copy
                    toggleBtn.text = "显示备份码"
                }
                dialog.show()
            } catch (e: Exception) {
                statusView.text = "生成备份失败：${e.message}"
            }
        }
    }

    private fun showRestoreDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 WTYB1- 开头的身份备份码"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 3
            setPadding(dp(16), dp(12), dp(16), dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        // Tiny inline error label inside the dialog so failures stay visible while the
        // user fixes their input. Default AlertDialog buttons auto-dismiss on click —
        // we rebind the positive button after show() so a CRC/format error keeps the
        // dialog open with the typed text intact.
        val errorView = TextView(this).apply {
            setTextColor(KeyboardUi.COLOR_DANGER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, matchWrap())
            addView(errorView, matchWrap())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("从备份恢复身份")
            .setMessage("这会覆盖当前设备的身份。身份改变时会清空旧会话并要求重新核对完整安全码；无法读取的联系人列表会被清空，之后需重新扫码。请确认输入完整。")
            .setView(container)
            .setPositiveButton("恢复", null)  // bound after show() so it doesn't auto-dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { input.setText("") }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val identity = KeyExchange.restoreIdentityFromBackup(
                    this@KeyManagementActivity,
                    input.text.toString(),
                    discardUnreadableContacts = true
                )
                statusView.text = "身份恢复成功 · ${identity.fingerprint}"
                refresh()
                dialog.dismiss()  // only on success — failure keeps the input visible
            } catch (e: Exception) {
                errorView.text = "❌ ${e.message ?: "恢复失败"}"
                errorView.visibility = View.VISIBLE
            }
        }
    }

    /** Adds a `XXXX-XXXX-…` rhythm and optionally masks all but prefix + last group. */
    private fun formatBackupForDisplay(backup: String, masked: Boolean): String {
        // backup already comes pre-grouped as `WTYB1-AAAAA-BBBBB-...`; respect that.
        if (!masked) return backup
        val parts = backup.split("-")
        if (parts.size <= 3) return "•••• … •••• ${parts.lastOrNull() ?: ""}"
        val prefix = parts[0]
        val first = parts[1]
        val last = parts.last()
        val maskedGroups = List(parts.size - 3) { "•••••" }
        return (listOf(prefix, first) + maskedGroups + last).joinToString("-")
    }

    // ─── Legacy passphrase ──────────────────────────────────────────────────

    private fun savePassphrase() {
        try {
            WentuyiSettings.setPassphrase(this, passphraseInput.text.toString())
            passphraseInput.setText("")
            refreshPassphraseStatus()
        } catch (e: IllegalArgumentException) {
            statusView.text = e.message ?: "密钥保存失败"
        } catch (e: RuntimeException) {
            statusView.text = "密钥保存失败：${e.message}"
        }
    }

    private fun refreshPassphraseStatus() {
        statusView.text = try {
            when {
                WentuyiSettings.hasSavedPassphrase(this) -> "共享密钥：已加密保存"
                WentuyiSettings.isUsingDefaultPassphrase(this) -> "共享密钥：开发默认密钥"
                else -> "共享密钥：未设置"
            }
        } catch (e: RuntimeException) { e.message ?: "未知" }
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Palette.textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Palette.textSubtle)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { action() }
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun matchWrapWithTop(topDp: Int): LinearLayout.LayoutParams =
        matchWrap().apply { topMargin = dp(topDp) }

    private fun weightWrap(weight: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat())

    private fun weightWrapWithLeft(weight: Int, leftDp: Int): LinearLayout.LayoutParams =
        weightWrap(weight).apply { leftMargin = dp(leftDp) }

    private fun dp(value: Int): Int = Math.round(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
    )
}
