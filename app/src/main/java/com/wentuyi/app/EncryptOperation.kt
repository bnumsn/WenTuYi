package com.wentuyi.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A composer's draft, recipient and encryption job, retained in memory across rotation. */
internal class EncryptOperation(
    context: Context,
    initialDraft: String,
    private val encryptMessage: (Context, SendTarget, String) -> MessageEncryptor.EncryptedText =
        MessageEncryptor::encryptText,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: (() -> Unit)? = null
    val selection = SendTargetSelection()
    var selectionInitialized = false
    var draft: String = initialDraft
        private set
    var state: State = State.Idle
        private set
    val busy: Boolean get() = state is State.Busy

    sealed class State {
        object Idle : State()
        object Busy : State()
        data class Success(val encrypted: MessageEncryptor.EncryptedText) : State()
        data class Failure(val message: String) : State()
    }

    fun attach(observer: () -> Unit) {
        this.observer = observer
        observer()
    }

    fun detach() { observer = null }

    fun close() {
        detach()
        scope.cancel()
        draft = ""
        state = State.Idle
    }

    /** Called on the main thread by edits; never carry a ciphertext into a new draft. */
    fun editDraft(text: String) {
        if (busy) return
        draft = text
        state = State.Idle
    }

    fun select(fingerprint: String?) {
        if (busy) return
        selection.select(fingerprint)
        selectionInitialized = true
        state = State.Idle
    }

    fun encrypt(target: SendTarget) {
        if (busy || draft.isEmpty()) return
        val plaintext = draft
        state = State.Busy
        observer?.invoke()
        scope.launch {
            try {
                val encrypted = withContext(Dispatchers.Default) {
                    encryptMessage(context, target, plaintext)
                }
                draft = ""
                state = State.Success(encrypted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.Failure(e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            }
            observer?.invoke()
        }
    }
}
