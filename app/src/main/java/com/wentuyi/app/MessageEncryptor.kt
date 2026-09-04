package com.wentuyi.app

import com.wentuyi.protocol.CryptoUtils
import com.wentuyi.protocol.SecurePayloadCodec

import android.content.Context

/**
 * "Encrypt this text for this target", in one place — the mirror image of [MessageDecryptor].
 *
 * This logic used to live inside [SendController], reachable only from the IME. Now that
 * text also arrives from the share sheet, the clipboard and text-selection menus
 * ([EncryptActivity]), a second copy would have meant two places encoding the two rules
 * that matter most here:
 *
 *  1. **Prefer forward secrecy.** A verified contact gets the WTY5 Double Ratchet. The WTY4
 *     session key is used only when the ratchet has no sending chain yet, and the caller is
 *     told so via [EncryptedText.noForwardSecrecy] rather than being downgraded silently.
 *  2. **Never downgrade the target.** [SendTarget.Unavailable] throws instead of quietly
 *     re-encrypting to the shared passphrase, which everyone holding the old shared key
 *     could read.
 */
object MessageEncryptor {

    /** Encrypted text + whether it had to fall back off the forward-secret ratchet path. */
    class EncryptedText(val payload: String, val noForwardSecrecy: Boolean)

    fun encryptText(context: Context, target: SendTarget, text: String): EncryptedText =
        when (target) {
            is SendTarget.SharedPassphrase -> EncryptedText(
                SecurePayloadCodec.encryptTextToPayload(text, WentuyiSettings.getPassphrase(context)),
                // A shared key is a deliberate choice by the user, not a downgrade.
                noForwardSecrecy = false,
            )

            is SendTarget.Contact -> {
                val ratchet =
                    RatchetSession.encryptText(context, target.identity, target.contact, text)
                if (ratchet != null) {
                    EncryptedText(ratchet, noForwardSecrecy = false)
                } else {
                    val secret =
                        KeyExchange.deriveSharedSecret(target.identity, target.contact.publicKey)
                    try {
                        EncryptedText(
                            SecurePayloadCodec.encryptTextWithSessionKey(text, secret),
                            noForwardSecrecy = true,
                        )
                    } finally {
                        CryptoUtils.wipe(secret)
                    }
                }
            }

            // Defensive: callers reject Unavailable before encrypting; never downgrade here.
            is SendTarget.Unavailable -> throw IllegalStateException(target.reason)
        }

    /**
     * The targets the user can pick from right now: the shared key (when configured) plus
     * every saved contact whose identity we can actually encrypt to.
     */
    fun availableTargets(context: Context): List<SendTarget> {
        val out = ArrayList<SendTarget>()
        if (WentuyiSettings.hasSavedPassphrase(context) || BuildConfig.DEBUG) {
            out += SendTarget.SharedPassphrase
        }
        val identity = runCatching { KeyExchange.loadIdentity(context) }.getOrNull()
        if (identity != null) {
            runCatching { KeyExchange.listContacts(context) }.getOrDefault(emptyList())
                .forEach { out += SendTarget.Contact(it, identity) }
        }
        return out
    }

    fun label(target: SendTarget): String = when (target) {
        is SendTarget.SharedPassphrase -> "共享密钥"
        is SendTarget.Contact ->
            if (target.contact.verified) target.contact.name else "${target.contact.name}（未验证）"
        is SendTarget.Unavailable -> "不可用"
    }
}
