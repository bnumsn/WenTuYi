package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * The action strip reads whatever text is already in the host's input box. Tap 密文
 * to replace text with an encrypted payload, tap 密图 to insert/share an encrypted QR,
 * and tap the target chip to choose shared-key vs contact encryption.
 */
class TextImageImeService : InputMethodService() {

    // ─── State ───────────────────────────────────────────────────────────────
    private var candidateContainer: LinearLayout? = null
    private var keyboardContainer: LinearLayout? = null
    private var decryptPanel: LinearLayout? = null
    private var decryptResultView: TextView? = null
    private var decryptImageView: ImageView? = null
    private var modeChip: TextView? = null
    private var targetChip: TextView? = null
    private val toolStripViews = ArrayList<View>()
    private var compactRowsApplied = false
    private var lastDecryptedText: String? = null
    private var lastDecryptedImageUri: Uri? = null

    private var chineseMode = true
    private var symbolsLayout = false
    private var shiftEnabled = false
    private var pinyinBuffer = ""

    /** 0 = shared passphrase; 1..N = the N-th contact (WTY5 ratchet / WTY4 fallback). */
    private var sendTargetIndex = 0
    private var imeSessionId = 0L

    /** 🖼 mode: false = plain pretty image, true = anti-OCR (noisy/jittered plaintext). */
    private var antiOcrMode = false
    private var cachedContacts: List<KeyExchange.Contact>? = null
    private var contactsPrefsListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val scope: CoroutineScope = MainScope()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var targetChipReset: Runnable? = null
    private var screenDecryptReceiver: BroadcastReceiver? = null
    private lateinit var sendController: SendController

    private companion object {
        /** Cap for getTextBefore/AfterCursor — large enough for any realistic message. */
        const val MAX_FIELD_CHARS = 100_000
        /**
         * Fallback bottom padding when the real inset can't be read. The old code always
         * used a hard-coded 34dp, which is dead space on a 3-button-navigation device or a
         * tablet and can be short of the gesture bar on a tall phone; [applyBottomInset]
         * now measures it and only falls back to this.
         */
        const val BOTTOM_SYSTEM_SAFE_AREA_DP = 20
    }

    override fun onCreate() {
        super.onCreate()
        Palette.refresh(this)
        KeyboardUi.setCompactRows(isLandscape())
        // ~700 KB of pinyin table. Parsing it on the main thread would stall the very first
        // keypress, so load it in the background and repaint the candidate strip when it
        // lands; until then candidatesFor() returns empty and raw pinyin still commits.
        scope.launch {
            withContext(Dispatchers.Default) { PinyinEngine.load(this@TextImageImeService) }
            if (pinyinBuffer.isNotEmpty()) refreshCandidates()
        }
        registerScreenDecryptReceiver()
        contactsPrefsListener = WentuyiSettings.watchContactsChanges(this) {
            cachedContacts = null
            updateTargetChip()
        }
    }

    override fun onCreateInputView(): View {
        sendController = SendController(this, scope, ::toast, { resolveSendTarget() }, { imeSessionId })
        toolStripViews.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(KeyboardUi.dp(context, 4), KeyboardUi.dp(context, 4),
                KeyboardUi.dp(context, 4), KeyboardUi.dp(context, BOTTOM_SYSTEM_SAFE_AREA_DP))
            setBackgroundColor(KeyboardUi.COLOR_PANEL)
        }
        applyBottomInset(root)

        // ── Candidate strip: [中/英] [candidates…] [target] [图] [密文] [密图] ──
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
            LinearLayout.LayoutParams.WRAP_CONTENT, KeyboardUi.candidateStripHeight(this)))

        candidateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(candidateContainer, LinearLayout.LayoutParams(
            0, KeyboardUi.candidateStripHeight(this), 1f))

        targetChip = TextView(this).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            setPadding(KeyboardUi.dp(context, 8), 0, KeyboardUi.dp(context, 8), 0)
            setOnClickListener { showTargetPicker() }
            setOnLongClickListener { showTargetPicker(); true }
        }
        bar.addView(targetChip, LinearLayout.LayoutParams(
            KeyboardUi.dp(this, 88), KeyboardUi.candidateStripHeight(this)).apply {
            leftMargin = KeyboardUi.dp(this@TextImageImeService, 4)
        })
        toolStripViews += targetChip!!

        // Three one-tap send actions. Plain text labels are intentional here: in testing,
        // icon-only 🖼 / 🔒 / ▦ looked disabled or unclear next to real keyboards.
        addToolAction(bar, sendAction("解", accent = false, contentDescription = "就地解密密文或二维码",
            onLong = { hideDecryptPanel() }) { decryptInKeyboard() }, 42)
        addToolAction(bar, sendAction("图", accent = false, contentDescription = "生成文字图片",
            onLong = { toggleAntiOcrWithToast() }) { sendPlainImage() }, 42)
        addToolAction(bar, sendAction("密文", accent = true, contentDescription = "写入加密文字",
            onLong = { showTargetPicker() }) { sendCipherText() }, 58)
        addToolAction(bar, sendAction("密图", accent = true, contentDescription = "插入或分享加密二维码",
            onLong = { showTargetPicker() }) { sendCipherQr() }, 58)
        root.addView(bar, KeyboardUi.matchWrap())

        root.addView(buildDecryptPanel(), KeyboardUi.matchWrapWithTop(this, 4))

        keyboardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardContainer, KeyboardUi.matchWrapWithTop(this, 4))

        refreshKeyboard()
        refreshCandidates()
        updateTargetChip()
        updateToolStripVisibility()
        consumePendingScreenDecryptResult()
        return root
    }

    /**
     * Night mode and rotation both change how the input view must be built (colours,
     * row heights), and neither can be patched in place, so the view is rebuilt. Guarded on
     * an actual change so an unrelated config change doesn't throw away a live keyboard.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val themeFlipped = Palette.refresh(this)
        val landscape = isLandscape()
        if (themeFlipped || landscape != compactRowsApplied) {
            compactRowsApplied = landscape
            KeyboardUi.setCompactRows(landscape)
            setInputView(onCreateInputView())
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * Pads the input view by the real navigation-bar inset rather than a fixed guess.
     * `rootWindowInsets` is only meaningful once attached, hence the attach listener.
     */
    private fun applyBottomInset(root: View) {
        fun bottomPx(): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root.rootWindowInsets?.let {
                    return it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                }
            } else {
                @Suppress("DEPRECATION")
                root.rootWindowInsets?.let { return it.systemWindowInsetBottom }
            }
            return KeyboardUi.dp(this, BOTTOM_SYSTEM_SAFE_AREA_DP)
        }
        fun apply() {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottomPx())
        }
        if (root.isAttachedToWindow) apply()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = apply()
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
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
        updateTargetChip()
        consumePendingScreenDecryptResult()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        pinyinBuffer = ""
        refreshCandidates()
    }

    override fun onDestroy() {
        targetChipReset?.let { uiHandler.removeCallbacks(it) }
        screenDecryptReceiver?.let { runCatching { unregisterReceiver(it) } }
        contactsPrefsListener?.let { WentuyiSettings.stopWatchingContacts(this, it) }
        scope.cancel()
        super.onDestroy()
    }

    // ─── In-chat decrypt panel ────────────────────────────────────────────────

    private sealed class DecryptInput {
        data class Payload(val text: String) : DecryptInput()
        data class Images(val uris: List<Uri>) : DecryptInput()
    }

    private fun buildDecryptPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(KeyboardUi.dp(context, 10), KeyboardUi.dp(context, 8),
                KeyboardUi.dp(context, 10), KeyboardUi.dp(context, 8))
            background = KeyboardUi.roundedSelector(context,
                KeyboardUi.COLOR_TOOLBAR_KEY, KeyboardUi.COLOR_TOOLBAR_KEY, 8,
                KeyboardUi.COLOR_STROKE, 1)
        }
        // Scrollable, not clipped. This used to be maxLines=3 + ellipsize, so a message
        // longer than three lines could not be read at all without copying it out — at the
        // exact moment the app finally delivers its payoff. Height is capped so the panel
        // can't push the keys off screen; beyond that the text scrolls.
        decryptResultView = TextView(this).apply {
            setTextColor(KeyboardUi.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(false)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            maxHeight = KeyboardUi.dp(context, if (compactRowsApplied) 64 else 108)
            text = ""
        }
        panel.addView(decryptResultView, KeyboardUi.matchWrap())
        decryptImageView = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = KeyboardUi.dp(context, 180)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        panel.addView(decryptImageView, KeyboardUi.matchWrapWithTop(this, 6))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(panelButton("写入") {
            val imageUri = lastDecryptedImageUri
            if (imageUri != null) {
                commitImageToCurrentInput(imageUri)
                return@panelButton
            }
            val text = lastDecryptedText
            if (text.isNullOrBlank()) toast("没有解密结果")
            else currentInputConnection?.commitText(text, 1) ?: toast("当前输入框不可写")
        }, KeyboardUi.toolbarParams(this, 0, 1f))
        actions.addView(panelButton("复制") {
            val imageUri = lastDecryptedImageUri
            if (imageUri != null) {
                copyImageToClipboard(imageUri)
                return@panelButton
            }
            val text = lastDecryptedText
            if (text.isNullOrBlank()) toast("没有解密结果")
            else copyTextToClipboard(text)
        }, KeyboardUi.toolbarParams(this, 6, 1f))
        actions.addView(panelButton("关闭") { hideDecryptPanel() },
            KeyboardUi.toolbarParams(this, 6, 1f))
        panel.addView(actions, KeyboardUi.matchWrapWithTop(this, 6))
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        decryptPanel = panel
        return panel
    }

    private fun panelButton(label: String, action: () -> Unit): Button =
        KeyboardUi.toolbarButton(this, label).apply {
            contentDescription = when (label) {
                "写入" -> "把解密结果写入当前输入框"
                "复制" -> "复制解密结果"
                "关闭" -> "关闭解密面板"
                else -> label
            }
            setOnClickListener { action() }
        }

    /**
     * Speaks [message] to TalkBack. Keyboard buttons are deliberately non-focusable (an IME
     * must not steal focus from the edited field), which also means a screen reader never
     * narrates what the keyboard just did — mode switches, decrypt results and send status
     * were all silent. Announcing from the input view restores that channel.
     */
    private fun announce(message: String) {
        if (message.isBlank()) return
        val host = decryptPanel ?: keyboardContainer ?: return
        host.announceForAccessibility(message)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenDecryptReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ScreenDecryptActivity.ACTION_RESULT) {
                    handleScreenDecryptResult(intent)
                }
            }
        }
        screenDecryptReceiver = receiver
        val filter = IntentFilter(ScreenDecryptActivity.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun requestScreenDecrypt() {
        lastDecryptedText = null
        lastDecryptedImageUri = null
        ScreenDecryptStore.clear(this)
        showDecryptPanel("正在请求屏幕截图权限...")
        showTransientTargetStatus("解图中")
        try {
            startActivity(Intent(this, ScreenDecryptActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            val msg = "解图失败：${e.userMessage()}"
            showDecryptPanel(msg)
            toast(msg)
        }
    }

    private fun consumePendingScreenDecryptResult() {
        if (decryptPanel == null || decryptResultView == null) return
        ScreenDecryptStore.consume(this)?.let { handleScreenDecryptResult(it) }
    }

    private fun handleScreenDecryptResult(intent: Intent) {
        if (decryptPanel == null || decryptResultView == null) return
        ScreenDecryptStore.clear(this)
        if (!intent.getBooleanExtra(ScreenDecryptActivity.EXTRA_OK, false)) {
            val msg = intent.getStringExtra(ScreenDecryptActivity.EXTRA_MESSAGE) ?: "解图失败"
            lastDecryptedText = null
            lastDecryptedImageUri = null
            showDecryptPanel(msg)
            toast(msg)
            return
        }
        val kind = intent.getStringExtra(ScreenDecryptActivity.EXTRA_KIND)
        if (kind == ScreenDecryptActivity.KIND_IMAGE) {
            val uri = intent.getStringExtra(ScreenDecryptActivity.EXTRA_IMAGE_URI)?.let(Uri::parse)
            if (uri == null) {
                showDecryptPanel("解图失败：图片结果丢失")
                return
            }
            lastDecryptedText = null
            lastDecryptedImageUri = uri
            showDecryptPanel(intent.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT) ?: "已解密一张图片", uri)
        } else {
            val text = intent.getStringExtra(ScreenDecryptActivity.EXTRA_TEXT).orEmpty()
            lastDecryptedText = text
            lastDecryptedImageUri = null
            showDecryptPanel(text)
        }
        showTransientTargetStatus("解密完成")
    }

    private fun decryptInKeyboard() {
        clearPinyinBuffer()
        val input = findDecryptInput()
        if (input == null) {
            requestScreenDecrypt()
            return
        }
        showDecryptPanel("正在解密...")
        toast("解密中")
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) { decryptInput(input) }
                lastDecryptedText = text
                lastDecryptedImageUri = null
                showDecryptPanel(text)
                showTransientTargetStatus("解密完成")
            } catch (e: Exception) {
                lastDecryptedText = null
                lastDecryptedImageUri = null
                val msg = "解密失败：${e.userMessage()}"
                showDecryptPanel(msg)
                toast(msg)
            }
        }
    }

    private fun findDecryptInput(): DecryptInput? {
        decryptPayloadFromCurrentInput()?.let { return DecryptInput.Payload(it) }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = clipboard.primaryClip ?: return null
        val uris = ArrayList<Uri>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i) ?: continue
            item.text?.toString()?.trim()?.takeIf { isWentuyiPayload(it) }?.let {
                return DecryptInput.Payload(it)
            }
            item.coerceToText(this)?.toString()?.trim()?.takeIf { isWentuyiPayload(it) }?.let {
                return DecryptInput.Payload(it)
            }
            item.uri?.let { uris += it }
        }
        return if (uris.isNotEmpty()) DecryptInput.Images(uris) else null
    }

    private fun decryptPayloadFromCurrentInput(): String? {
        val ic = currentInputConnection ?: return null
        ic.getSelectedText(0)?.toString()?.trim()?.takeIf { isWentuyiPayload(it) }?.let { return it }
        return readInputBoxText().trim().takeIf { isWentuyiPayload(it) }
    }

    private fun decryptInput(input: DecryptInput): String {
        val payload = when (input) {
            is DecryptInput.Payload -> input.text
            is DecryptInput.Images -> {
                val bitmaps = input.uris.map { BitmapUtils.decodeImportImage(contentResolver, it) }
                val qrTexts = bitmaps.map { TextImageCodec.readQrText(it) }
                TextImageCodec.assemblePayloadFromTexts(qrTexts)
            }
        }
        return when (val result = MessageDecryptor.decrypt(this, payload)) {
            is MessageDecryptor.Result.Success -> {
                if (result.payload.isText()) result.payload.text()
                else "已解密一张图片，请用“文图易解密”分享入口查看图片"
            }
            is MessageDecryptor.Result.Failure -> throw IllegalArgumentException(result.message)
        }
    }

    private fun showDecryptPanel(text: String, imageUri: Uri? = null) {
        decryptResultView?.text = text
        if (imageUri == null) {
            decryptImageView?.visibility = View.GONE
            decryptImageView?.setImageDrawable(null)
        } else {
            decryptImageView?.setImageURI(imageUri)
            decryptImageView?.visibility = View.VISIBLE
        }
        decryptPanel?.visibility = View.VISIBLE
    }

    private fun hideDecryptPanel() {
        lastDecryptedText = null
        lastDecryptedImageUri = null
        decryptResultView?.text = ""
        decryptImageView?.setImageDrawable(null)
        decryptImageView?.visibility = View.GONE
        decryptPanel?.visibility = View.GONE
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast("无法访问剪贴板")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("文图易解密文本", text))
        toast("结果已复制")
    }

    private fun copyImageToClipboard(uri: Uri) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast("无法访问剪贴板")
            return
        }
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "文图易解密图片", uri))
        toast("图片已复制")
    }

    private fun commitImageToCurrentInput(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            toast("当前系统不支持写入图片")
            return
        }
        val connection = currentInputConnection ?: run {
            toast("当前输入框不可写")
            return
        }
        val editorInfo = currentInputEditorInfo ?: run {
            toast("当前输入框不支持图片")
            return
        }
        val mimeTypes = editorInfo.contentMimeTypes ?: emptyArray()
        if (!mimeTypes.any { it.equals("image/png", true) || it.equals("image/*", true) }) {
            toast("当前输入框不支持图片")
            return
        }
        editorInfo.packageName?.let {
            grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val info = InputContentInfo(uri, ClipDescription("文图易解密图片", arrayOf("image/png")), null)
        val ok = connection.commitContent(
            info,
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            Bundle(),
        )
        toast(if (ok) "图片已写入" else "图片写入失败")
    }

    private fun isWentuyiPayload(text: String): Boolean =
        SecurePayloadCodec.isPayload(text) || text.startsWith(DoubleRatchet.PREFIX_V5)

    // ─── Candidate strip ─────────────────────────────────────────────────────

    private fun refreshCandidates() {
        updateModeChip()
        val container = candidateContainer ?: return
        container.removeAllViews()
        updateToolStripVisibility()

        if (!chineseMode || symbolsLayout || pinyinBuffer.isEmpty()) {
            // Idle strip — like Gboard, blank when not composing pinyin. The 中/英
            // state lives in the always-visible modeChip on the left.
            return
        }

        val real = PinyinEngine.candidatesFor(pinyinBuffer).filter { it != pinyinBuffer }
        val rawButton = KeyboardUi.rawPinyinButton(this, pinyinBuffer).apply {
            contentDescription = "按原样上屏拼音字母 $pinyinBuffer"
            setOnClickListener { commitRawPinyin() }
        }
        val rawWeight = if (real.isEmpty()) 6.0f else 0.9f
        container.addView(rawButton, KeyboardUi.candidateParams(this, 0, rawWeight))
        real.take(6).forEachIndexed { idx, candidate ->
            val button = KeyboardUi.candidateButton(this, candidate).apply {
                if (idx == 0) KeyboardUi.styleFirstCandidate(this@TextImageImeService, this)
                contentDescription =
                    if (idx == 0) "首选候选 $candidate，按空格上屏" else "候选 ${idx + 1} $candidate"
                setOnClickListener { commitPinyinCandidate(candidate) }
            }
            container.addView(button, KeyboardUi.candidateParams(this, 4, 1.0f))
        }
    }

    private fun updateModeChip() {
        val chip = modeChip ?: return
        chip.contentDescription =
            if (chineseMode) "当前中文拼音输入，点按切换到英文" else "当前英文直输，点按切换到中文拼音"
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

    private fun updateToolStripVisibility() {
        val composing = chineseMode && !symbolsLayout && pinyinBuffer.isNotEmpty()
        val visibility = if (composing) View.GONE else View.VISIBLE
        for (view in toolStripViews) view.visibility = visibility
    }

    private fun updateTargetChip() {
        val chip = targetChip ?: return
        targetChipReset?.let { uiHandler.removeCallbacks(it) }
        targetChipReset = null
        chip.text = currentTargetName()
        chip.contentDescription = "当前加密目标：${currentTargetName()}，点按更换"
        chip.setTextColor(if (sendTargetIndex == 0) KeyboardUi.COLOR_SUBTLE else KeyboardUi.COLOR_ACCENT)
        chip.background = KeyboardUi.roundedSelector(
            this,
            if (sendTargetIndex == 0) KeyboardUi.COLOR_TOOLBAR_KEY else KeyboardUi.COLOR_ACCENT_TINT,
            if (sendTargetIndex == 0) KeyboardUi.COLOR_TOOLBAR_PRESSED else KeyboardUi.COLOR_ACCENT_TINT_PRESSED,
            14,
            if (sendTargetIndex == 0) KeyboardUi.COLOR_STROKE else KeyboardUi.COLOR_ACCENT,
            1,
        )
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
        KeyboardUi.keyboardButton(this, label, false).apply {
            contentDescription = keyDescription(label)
            setOnClickListener { handleTextKey(value) }
        }

    /**
     * TalkBack reads a key's label, which is fine for letters but useless for punctuation —
     * "⌫" and "@" get announced as their raw glyph or skipped entirely. Naming them keeps
     * the keyboard navigable by ear.
     */
    private fun keyDescription(label: String): String = when (label) {
        "⌫" -> "退格"
        "⇧" -> "上档"
        "↵" -> "回车"
        "123" -> "切换到数字符号"
        "ABC" -> "切换到字母"
        "@" -> "at 符号"; "#" -> "井号"; "$" -> "美元符号"; "_" -> "下划线"
        "&" -> "和号"; "-" -> "减号"; "+" -> "加号"; "(" -> "左括号"; ")" -> "右括号"
        "/" -> "斜杠"; "*" -> "星号"; "\"" -> "双引号"; "'" -> "单引号"
        ":" -> "冒号"; ";" -> "分号"; "!" -> "感叹号"; "?" -> "问号"
        else -> label
    }

    private fun controlKey(label: String, active: Boolean = false, action: () -> Unit): Button {
        val button = KeyboardUi.keyboardButton(this, label, true)
        if (active) KeyboardUi.styleActiveKey(this, button)
        button.contentDescription = keyDescription(label) + if (active) "，已启用" else ""
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
        announce(if (chineseMode) "已切换到英文直输" else "已切换到中文拼音")
        pinyinBuffer = ""
        chineseMode = !chineseMode
        symbolsLayout = false
        shiftEnabled = false
        refreshKeyboard()
    }

    private fun commitFirstPinyinCandidate(): Boolean {
        if (pinyinBuffer.isEmpty()) return false
        writeTextToActiveTarget(PinyinEngine.firstCandidateOrRaw(pinyinBuffer))
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

    private fun sendAction(
        label: String,
        accent: Boolean,
        contentDescription: String,
        onLong: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            this.contentDescription = contentDescription
            isAllCaps = false
            transformationMethod = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 13f else 15f)
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
                setTextColor(Color.WHITE)
                background = KeyboardUi.roundedSelector(this@TextImageImeService,
                    KeyboardUi.COLOR_ACCENT, KeyboardUi.COLOR_ACCENT_PRESSED, 14,
                    Color.TRANSPARENT, 0)
            } else {
                setTextColor(KeyboardUi.COLOR_TEXT)
                background = KeyboardUi.roundedSelector(this@TextImageImeService,
                    KeyboardUi.COLOR_TOOLBAR_KEY, KeyboardUi.COLOR_TOOLBAR_PRESSED, 14,
                    KeyboardUi.COLOR_STROKE, 1)
            }
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            onLong?.let { handler -> setOnLongClickListener { handler(); true } }
        }

    private fun sendActionParams(widthDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(KeyboardUi.dp(this, widthDp), KeyboardUi.dp(this, 40)).apply {
            leftMargin = KeyboardUi.dp(this@TextImageImeService, 4)
        }

    private fun addToolAction(bar: LinearLayout, button: Button, widthDp: Int) {
        bar.addView(button, sendActionParams(widthDp))
        toolStripViews += button
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

    /** 🔒 — encrypt the input-box text and replace it with a WTY4 / WTY5 ciphertext. */
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

    private fun showTargetPicker() {
        val contactList = contacts()
        if (contactList.isEmpty()) {
            toast("加密目标：共享密钥（还没有联系人，去主 App 扫码加好友）")
            return
        }
        val labels = arrayOf("共享密钥") + contactList.map { contactDisplayName(it) }.toTypedArray()
        sendTargetIndex = sendTargetIndex.coerceIn(0, labels.lastIndex)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择加密目标")
            .setSingleChoiceItems(labels, sendTargetIndex) { d, which ->
                sendTargetIndex = which
                updateTargetChip()
                toast("加密目标：${currentTargetName()}")
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
        attachToImeWindow(dialog)
        try {
            dialog.show()
        } catch (e: RuntimeException) {
            cycleTargetFallback()
        }
    }

    private fun attachToImeWindow(dialog: AlertDialog) {
        val token = window.window?.attributes?.token ?: return
        val dialogWindow = dialog.window ?: return
        dialogWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        val attrs = dialogWindow.attributes
        attrs.token = token
        dialogWindow.attributes = attrs
    }

    private fun cycleTargetFallback() {
        val n = 1 + contacts().size
        sendTargetIndex = (sendTargetIndex + 1) % n
        updateTargetChip()
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

    /**
     * Status feedback. The target chip is the primary channel — it sits inside the keyboard,
     * needs no dismissal and covers nothing. A Toast from an IME floats over the very field
     * the user is typing into, so it is now reserved for messages that actually need
     * attention (failures, refusals, target changes); routine "已写密文" confirmations stay
     * in the chip. Everything is announced for TalkBack either way.
     */
    private fun toast(message: String) {
        if (message.isBlank()) return
        showTransientTargetStatus(message)
        announce(message)
        if (needsAttention(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun needsAttention(message: String): Boolean =
        message.contains("失败") || message.contains("不可") || message.contains("没有") ||
            message.contains("变化") || message.contains("请先") || message.contains("过大") ||
            message.contains("未") || message.contains("无法") || message.contains("已切换")

    private fun showTransientTargetStatus(message: String) {
        val chip = targetChip ?: return
        targetChipReset?.let { uiHandler.removeCallbacks(it) }
        val short = compactStatus(message)
        chip.text = short
        chip.setTextColor(when {
            short.contains("失败") || short.contains("不可") || short.contains("变化") -> KeyboardUi.COLOR_DANGER
            short.startsWith("已") || short.endsWith("中") -> KeyboardUi.COLOR_ACCENT
            else -> KeyboardUi.COLOR_SUBTLE
        })
        chip.background = KeyboardUi.roundedSelector(
            this,
            KeyboardUi.COLOR_TOOLBAR_KEY,
            KeyboardUi.COLOR_TOOLBAR_PRESSED,
            14,
            KeyboardUi.COLOR_STROKE,
            1,
        )
        val reset = Runnable { updateTargetChip() }
        targetChipReset = reset
        uiHandler.postDelayed(reset, 2200L)
    }

    private fun compactStatus(message: String): String = when {
        message.startsWith("正在加密文字") -> "加密中"
        message.startsWith("正在生成加密二维码") -> "制密图中"
        message.startsWith("正在生成图片") -> "制图中"
        message.startsWith("正在生成防 OCR") -> "制图中"
        message.startsWith("已写入加密文字") -> "已写密文"
        message.startsWith("已插入加密二维码") -> "已插密图"
        message.startsWith("已分享加密二维码") -> "已分享密图"
        message.startsWith("已插入文字图片") -> "已插图片"
        message.startsWith("已分享文字图片") -> "已分享图片"
        message.startsWith("输入框没有文字") -> "先输入文字"
        message.startsWith("当前输入框不可写") -> "不可写"
        message.startsWith("输入内容已变化") -> "内容变化"
        message.startsWith("目标已切换") -> "目标切换"
        message.startsWith("加密失败") -> "加密失败"
        message.startsWith("解图中") -> "解图中"
        message.startsWith("解密失败") -> "解密失败"
        message.startsWith("解密完成") -> "解密完成"
        message.startsWith("生成失败") -> "生成失败"
        message.startsWith("写入失败") -> "写入失败"
        message.startsWith("加密目标") -> currentTargetName()
        else -> message.take(6)
    }

    private fun Exception.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
