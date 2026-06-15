package com.wentuyi.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Wentuyi IME — Gboard-style layout (v0.6 redesign).
 *
 * The v0.5 keyboard piled four non-keyboard rows on top of the QWERTY (a 9-button
 * toolbar, a verified-contact hint, an encryption-target line, and a mode banner),
 * squeezing the actual keys into a sliver and not looking like a normal IME at all.
 *
 * v0.6 strips all of that. The layout is now exactly what users expect from a
 * mainstream keyboard:
 *
 *   ┌──────────────────────────────┐
 *   │ [中] 你好 你号 你 …       🔒 │  ← single candidate strip + crypto entry
 *   ├──────────────────────────────┤
 *   │  q w e r t y u i o p          │
 *   │  a s d f g h j k l            │
 *   │  ⇧  z x c v b n m  ⌫         │
 *   │ ?123  中/英  ___空格___ 。 ↵ │
 *   └──────────────────────────────┘
 *
 * The keyboard itself just types into the host app (no more 直输/编辑 dual mode).
 * All encryption lives behind the 🔒 button: it reads whatever text is already in
 * the host's input box, then offers 密文 / 密图 / 普通图片 and a send-target picker
 * in a dialog. Status feedback is a transient Toast, not a permanent status row.
 */
class TextImageImeService : InputMethodService() {

    // ─── State ───────────────────────────────────────────────────────────────
    private var candidateContainer: LinearLayout? = null
    private var keyboardContainer: LinearLayout? = null
    private var modeChip: TextView? = null

    private var chineseMode = true
    private var symbolsLayout = false
    private var shiftEnabled = false
    private var pinyinBuffer = ""

    /** 0 = shared passphrase; 1..N = the N-th contact (WTY3 session key). */
    private var sendTargetIndex = 0
    private var imeSessionId = 0L

    /** 🖼 mode: false = plain pretty image, true = anti-OCR (noisy/jittered plaintext). */
    private var antiOcrMode = false
    private var cachedContacts: List<KeyExchange.Contact>? = null
    private var contactsPrefsListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val scope: CoroutineScope = MainScope()
    private lateinit var sendController: SendController

    private companion object {
        /** Cap for getTextBefore/AfterCursor — large enough for any realistic message. */
        const val MAX_FIELD_CHARS = 100_000
    }

    override fun onCreate() {
        super.onCreate()
        contactsPrefsListener = WentuyiSettings.watchContactsChanges(this) {
            cachedContacts = null
        }
    }

    override fun onCreateInputView(): View {
        sendController = SendController(this, scope, ::toast, { resolveSendTarget() }, { imeSessionId })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(KeyboardUi.dp(context, 4), KeyboardUi.dp(context, 4),
                KeyboardUi.dp(context, 4), KeyboardUi.dp(context, 4))
            setBackgroundColor(KeyboardUi.COLOR_PANEL)
        }

        // ── Candidate strip: [中/英 chip] [candidates…] [🔒] ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeChip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(KeyboardUi.dp(context, 10), 0, KeyboardUi.dp(context, 10), 0)
            setOnClickListener { toggleLanguageMode() }
        }
        bar.addView(modeChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, KeyboardUi.dp(this, 44)))

        candidateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(candidateContainer, LinearLayout.LayoutParams(0, KeyboardUi.dp(this, 44), 1f))

        // Three one-tap send icons on the right of the candidate strip. No more
        // 🔒 → panel two-step: tapping each icon reads the current input-box text and
        // immediately performs the action. 图片 = plain text→image (not encrypted),
        // 密文 = encrypted text replacing the box, 二维码 = encrypted QR image.
        // Long-press 密文 / 二维码 cycles the encryption target.
        bar.addView(sendIcon("🖼", accent = false, onLong = { toggleAntiOcrWithToast() }) { sendPlainImage() },
            sendIconParams())
        bar.addView(sendIcon("🔒", accent = true, onLong = { cycleTargetWithToast() }) { sendCipherText() },
            sendIconParams())
        bar.addView(sendIcon("▦", accent = true, onLong = { cycleTargetWithToast() }) { sendCipherQr() },
            sendIconParams())
        root.addView(bar, KeyboardUi.matchWrap())

        keyboardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardContainer, KeyboardUi.matchWrapWithTop(this, 4))

        refreshKeyboard()
        refreshCandidates()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            pinyinBuffer = ""
            shiftEnabled = false
            imeSessionId++
        }
        cachedContacts = null
        refreshCandidates()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        pinyinBuffer = ""
        refreshCandidates()
    }

    override fun onDestroy() {
        contactsPrefsListener?.let { WentuyiSettings.stopWatchingContacts(this, it) }
        scope.cancel()
        super.onDestroy()
    }

    // ─── Candidate strip ─────────────────────────────────────────────────────

    private fun refreshCandidates() {
        updateModeChip()
        val container = candidateContainer ?: return
        container.removeAllViews()

        if (!chineseMode || symbolsLayout || pinyinBuffer.isEmpty()) {
            // Idle strip — like Gboard, blank when not composing pinyin. The 中/英
            // state lives in the always-visible modeChip on the left.
            return
        }

        val real = PinyinCandidates.candidatesFor(pinyinBuffer).filter { it != pinyinBuffer }
        val rawButton = KeyboardUi.rawPinyinButton(this, pinyinBuffer).apply {
            setOnClickListener { commitRawPinyin() }
        }
        val rawWeight = if (real.isEmpty()) 6.0f else 0.9f
        container.addView(rawButton, KeyboardUi.candidateParams(this, 0, rawWeight))
        real.take(6).forEachIndexed { idx, candidate ->
            val button = KeyboardUi.candidateButton(this, candidate).apply {
                if (idx == 0) KeyboardUi.styleFirstCandidate(this@TextImageImeService, this)
                setOnClickListener { commitPinyinCandidate(candidate) }
            }
            container.addView(button, KeyboardUi.candidateParams(this, 4, 1.0f))
        }
    }

    private fun updateModeChip() {
        val chip = modeChip ?: return
        if (chineseMode) {
            chip.text = "中"
            chip.setTextColor(Color.WHITE)
            chip.background = KeyboardUi.roundedSelector(this,
                KeyboardUi.COLOR_ACCENT, KeyboardUi.COLOR_ACCENT_PRESSED, 14, Color.TRANSPARENT, 0)
        } else {
            chip.text = "En"
            chip.setTextColor(KeyboardUi.COLOR_TEXT)
            chip.background = KeyboardUi.roundedSelector(this,
                KeyboardUi.COLOR_TOOLBAR_KEY, KeyboardUi.COLOR_TOOLBAR_PRESSED, 14, Color.TRANSPARENT, 0)
        }
    }

    // ─── Keyboard rendering ─────────────────────────────────────────────────

    private fun refreshKeyboard() {
        val container = keyboardContainer ?: return
        container.removeAllViews()
        if (symbolsLayout) {
            addKeyRow(arrayOf("1","2","3","4","5","6","7","8","9","0"))
            addKeyRow(arrayOf("@","#","$","_","&","-","+","(",")"))
            addSymbolRowWithBackspace(arrayOf("/","*","\"","'",":",";","!","?"))
            addControlRow("ABC")
        } else {
            addKeyRow(labelsForLetters(arrayOf("q","w","e","r","t","y","u","i","o","p")))
            addKeyRow(labelsForLetters(arrayOf("a","s","d","f","g","h","j","k","l")))
            addKeyRowWithControls(labelsForLetters(arrayOf("z","x","c","v","b","n","m")))
            addControlRow("123")
        }
        refreshCandidates()
    }

    private fun labelsForLetters(letters: Array<String>): Array<String> =
        if (chineseMode || !shiftEnabled) letters
        else Array(letters.size) { letters[it].uppercase() }

    private fun addKeyRow(labels: Array<String>) {
        val row = keyboardRow()
        labels.forEachIndexed { i, l ->
            row.addView(keyButton(l, l), KeyboardUi.keyParams(this, if (i == 0) 0 else 4, 1.0f))
        }
        keyboardContainer?.addView(row, KeyboardUi.matchWrapWithTop(this, 4))
    }

    private fun addSymbolRowWithBackspace(labels: Array<String>) {
        val row = keyboardRow()
        labels.forEachIndexed { i, l ->
            row.addView(keyButton(l, l), KeyboardUi.keyParams(this, if (i == 0) 0 else 4, 1.0f))
        }
        row.addView(controlKey("⌫") { handleBackspace() }, KeyboardUi.keyParams(this, 4, 1.4f))
        keyboardContainer?.addView(row, KeyboardUi.matchWrapWithTop(this, 4))
    }

    private fun addKeyRowWithControls(labels: Array<String>) {
        val row = keyboardRow()
        val shift = controlKey("⇧", active = shiftEnabled) {
            shiftEnabled = !shiftEnabled; refreshKeyboard()
        }
        row.addView(shift, KeyboardUi.keyParams(this, 0, 1.4f))
        for (l in labels) row.addView(keyButton(l, l), KeyboardUi.keyParams(this, 4, 1.0f))
        row.addView(controlKey("⌫") { handleBackspace() }, KeyboardUi.keyParams(this, 4, 1.4f))
        keyboardContainer?.addView(row, KeyboardUi.matchWrapWithTop(this, 4))
    }

    private fun addControlRow(layoutToggleLabel: String) {
        val row = keyboardRow()
        val layoutBtn = controlKey(layoutToggleLabel) {
            symbolsLayout = !symbolsLayout; shiftEnabled = false; refreshKeyboard()
        }
        row.addView(layoutBtn, KeyboardUi.keyParams(this, 0, 1.3f))
        row.addView(
            controlKey(if (chineseMode) "中" else "英", active = chineseMode) { toggleLanguageMode() },
            KeyboardUi.keyParams(this, 4, 1.0f)
        )
        row.addView(keyButton(",", ","), KeyboardUi.keyParams(this, 4, 0.6f))
        row.addView(keyButton(if (chineseMode) "空格" else "space", " "),
            KeyboardUi.keyParams(this, 4, 4.4f))
        row.addView(keyButton(".", "."), KeyboardUi.keyParams(this, 4, 0.6f))
        row.addView(controlKey("↵") { handleEnter() }, KeyboardUi.keyParams(this, 4, 1.3f))
        keyboardContainer?.addView(row, KeyboardUi.matchWrapWithTop(this, 4))
    }

    private fun keyboardRow(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun keyButton(label: String, value: String): Button =
        KeyboardUi.keyboardButton(this, label, false).apply { setOnClickListener { handleTextKey(value) } }

    private fun controlKey(label: String, active: Boolean = false, action: () -> Unit): Button {
        val button = KeyboardUi.keyboardButton(this, label, true)
        if (active) KeyboardUi.styleActiveKey(this, button)
        button.setOnClickListener { action() }
        return button
    }

    // ─── Pinyin + key handling ──────────────────────────────────────────────

    private fun handleTextKey(text: String) {
        if (chineseMode && !symbolsLayout) { handleChineseTextKey(text); return }
        writeTextToActiveTarget(text)
        disableOneShotShiftIfNeeded(text)
    }

    private fun handleChineseTextKey(text: String) {
        if (text.length == 1 && isAsciiLetter(text[0])) {
            pinyinBuffer += text.lowercase()
            refreshCandidates(); return
        }
        if (text == " ") {
            if (!commitFirstPinyinCandidate()) writeTextToActiveTarget(text)
            return
        }
        if (pinyinBuffer.isNotEmpty()) commitFirstPinyinCandidate()
        writeTextToActiveTarget(chinesePunctuation(text))
    }

    private fun writeTextToActiveTarget(text: String) {
        val connection = currentInputConnection ?: run { toast("当前输入框不可写"); return }
        connection.commitText(text, 1)
    }

    private fun disableOneShotShiftIfNeeded(text: String) {
        if (shiftEnabled && !symbolsLayout && text.length == 1 && Character.isLetter(text[0])) {
            shiftEnabled = false
            refreshKeyboard()
        }
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun chinesePunctuation(text: String): String = when (text) {
        "," -> "，"
        "." -> "。"
        else -> text
    }

    private fun toggleLanguageMode() {
        pinyinBuffer = ""
        chineseMode = !chineseMode
        symbolsLayout = false
        shiftEnabled = false
        refreshKeyboard()
    }

    private fun commitFirstPinyinCandidate(): Boolean {
        if (pinyinBuffer.isEmpty()) return false
        writeTextToActiveTarget(PinyinCandidates.firstCandidateOrRaw(pinyinBuffer))
        clearPinyinBuffer()
        return true
    }

    private fun clearPinyinBuffer() {
        if (pinyinBuffer.isNotEmpty()) pinyinBuffer = ""
        refreshCandidates()
    }

    private fun commitPinyinCandidate(candidate: String) {
        writeTextToActiveTarget(candidate)
        clearPinyinBuffer()
    }

    private fun commitRawPinyin() {
        if (pinyinBuffer.isEmpty()) return
        writeTextToActiveTarget(pinyinBuffer)
        clearPinyinBuffer()
    }

    private fun handleBackspace() {
        if (pinyinBuffer.isNotEmpty()) {
            pinyinBuffer = pinyinBuffer.substring(0, pinyinBuffer.length - 1)
            refreshCandidates(); return
        }
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) connection.commitText("", 1)
        else connection.deleteSurroundingText(1, 0)
    }

    private fun handleEnter() {
        if (pinyinBuffer.isNotEmpty()) { commitFirstPinyinCandidate(); return }
        val connection = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            connection.commitText("\n", 1)
        } else {
            // Single-line field: fire the editor's action (send/search/done) like a
            // normal keyboard, falling back to a newline if there's no action.
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                connection.performEditorAction(action)
            } else {
                connection.commitText("\n", 1)
            }
        }
    }

    private fun switchInputMethod() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToNextInputMethod(false)) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            ?: toast("无法切换输入法")
    }

    // ─── 🔒 Crypto menu ──────────────────────────────────────────────────────

    // ─── Three one-tap send icons ────────────────────────────────────────────

    private fun sendIcon(icon: String, accent: Boolean, onLong: (() -> Unit)? = null,
                         onClick: () -> Unit): Button =
        Button(this).apply {
            text = icon
            isAllCaps = false
            transformationMethod = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, 0)
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            // Match the working keyboard keys: non-focusable so the IME view never
            // steals focus, and no elevation animator that can swallow a quick tap.
            isFocusable = false
            isFocusableInTouchMode = false
            stateListAnimator = null
            if (accent) {
                setTextColor(KeyboardUi.COLOR_ACCENT)
                background = KeyboardUi.roundedSelector(this@TextImageImeService,
                    KeyboardUi.COLOR_ACCENT_TINT, KeyboardUi.COLOR_ACCENT_TINT_PRESSED, 12,
                    KeyboardUi.COLOR_ACCENT, 1)
            } else {
                setTextColor(KeyboardUi.COLOR_TEXT)
                background = KeyboardUi.roundedSelector(this@TextImageImeService,
                    KeyboardUi.COLOR_TOOLBAR_KEY, KeyboardUi.COLOR_TOOLBAR_PRESSED, 12,
                    Color.TRANSPARENT, 0)
            }
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            onLong?.let { handler -> setOnLongClickListener { handler(); true } }
        }

    private fun sendIconParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(KeyboardUi.dp(this, 50), KeyboardUi.dp(this, 44)).apply {
            leftMargin = KeyboardUi.dp(this@TextImageImeService, 4)
        }

    /** 🖼 — render the input-box text to a plain (unencrypted) PNG and send it.
     *  Long-press toggles anti-OCR mode (readable to humans, noisy to machine OCR). */
    private fun sendPlainImage() {
        val text = readInputBoxText()
        if (text.isBlank()) { toast("输入框没有文字，先打字再点"); return }
        if (antiOcrMode) sendController.generateAntiOcrImage(text)
        else sendController.generatePlainTextImage(text)
    }

    private fun toggleAntiOcrWithToast() {
        antiOcrMode = !antiOcrMode
        toast(if (antiOcrMode) "图片模式：防 OCR（明文但防机器识别）" else "图片模式：普通文字图")
    }

    /** 🔒 — encrypt the input-box text and replace it with the WTY3 ciphertext. */
    private fun sendCipherText() {
        val text = readInputBoxText()
        if (text.isBlank()) { toast("输入框没有文字，先打字再点"); return }
        sendController.commitEncryptedText(text)
    }

    /** ▦ — encrypt the input-box text into a QR image and send it. */
    private fun sendCipherQr() {
        val text = readInputBoxText()
        if (text.isBlank()) { toast("输入框没有文字，先打字再点"); return }
        sendController.generateEncryptedImage(text)
    }

    private fun cycleTargetWithToast() {
        val n = 1 + contacts().size
        if (n <= 1) {
            toast("加密目标：共享密钥（还没有联系人，去主 App 扫码加好友）")
            return
        }
        sendTargetIndex = (sendTargetIndex + 1) % n
        toast("加密目标：${currentTargetName()}")
    }

    private fun readInputBoxText(): String {
        val ic = currentInputConnection ?: return ""
        ic.getSelectedText(0)?.takeIf { it.isNotEmpty() }?.let { return it.toString() }
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        if (!ext.isNullOrEmpty()) return ext
        // WeChat / QQ / many WebView chats return null from getExtractedText. Fall
        // back to reading around the cursor so the send icons still see the text.
        val before = ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_FIELD_CHARS, 0)?.toString().orEmpty()
        return before + after
    }

    private fun currentTargetName(): String {
        if (sendTargetIndex == 0) return "共享密钥"
        val contact = contacts().getOrNull(sendTargetIndex - 1) ?: return "共享密钥"
        return contactDisplayName(contact)
    }

    // ─── Send-target resolution ──────────────────────────────────────────────

    private fun resolveSendTarget(): SendController.SendTarget {
        if (sendTargetIndex == 0) return SendController.SendTarget.SharedPassphrase
        // The user picked a specific contact. If we can't honour that exactly, refuse —
        // never silently re-encrypt to the shared passphrase, which everyone holding the
        // old shared key could read. Fail closed and tell the user to re-pick.
        val contact = contacts().getOrNull(sendTargetIndex - 1)
            ?: return SendController.SendTarget.Unavailable("所选联系人已不存在，请长按加密图标重新选择目标")
        val identity = runCatching { KeyExchange.loadIdentity(this) }.getOrNull()
            ?: return SendController.SendTarget.Unavailable("身份密钥读取失败，无法按联系人加密；请到主 App 检查身份")
        return SendController.SendTarget.Contact(contact, identity)
    }

    private fun contacts(): List<KeyExchange.Contact> {
        cachedContacts?.let { return it }
        return runCatching { KeyExchange.listContacts(this) }
            .getOrDefault(emptyList())
            .also { cachedContacts = it }
    }

    private fun contactDisplayName(contact: KeyExchange.Contact): String =
        if (contact.verified) contact.name else "${contact.name}（未验证）"

    private fun toast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
