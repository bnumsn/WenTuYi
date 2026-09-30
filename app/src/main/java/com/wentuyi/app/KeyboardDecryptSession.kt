package com.wentuyi.app

import android.content.Context
import android.net.Uri

/** One service-local result, always bound to the editor and input that requested it. */
internal class KeyboardDecryptSession(
    context: Context,
    decryptMessage: (Context, String) -> MessageDecryptor.Result = MessageDecryptor::decrypt,
) {
    data class Anchor(val packageName: String?, val fieldId: Int, val session: Long)
    sealed class Input {
        data class Payload(val text: String, val fromInputBox: Boolean) : Input()
        data class Images(val uris: List<Uri>) : Input()
    }

    val operation = DecryptOperation(context, decryptMessage)
    var anchor: Anchor? = null
        private set
    var input: Input? = null
        private set

    /** A caller may view a retained result elsewhere, but only this anchor may insert it. */
    fun canWriteTo(current: Anchor?): Boolean = current != null && anchor == current

    fun submit(target: Anchor, source: Input) {
        if (operation.state is DecryptOperation.State.Busy) return
        val previous = input
        val sameInput = when {
            previous is Input.Payload && source is Input.Payload -> previous.text == source.text
            else -> source == previous
        }
        if (sameInput && operation.state is DecryptOperation.State.Success) return
        anchor = target
        input = source
        when (source) {
            is Input.Payload -> operation.decryptText(source.text)
            is Input.Images -> operation.decryptImages(source.uris)
        }
    }
}
