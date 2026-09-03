package com.wentuyi.app

import com.wentuyi.protocol.CryptoUtils

import com.wentuyi.protocol.SecurePayloadCodec

import android.content.Context
import java.security.GeneralSecurityException

/**
 * One central place for "decrypt a Wentuyi payload regardless of who sent it".
 *
 * v0.4 had this routing duplicated verbatim inside [DecryptActivity] and
 * [ScanActivity]; any behavioural change had to be made in two places and one of
 * them invariably drifted. v0.5 collapses both to this object and replaces the
 * single string "decrypt failed" message with a structured [Reason] so the UI can
 * differentiate "wrong format" (give up) from "contact not found" (suggest adding
 * the peer) from "shared key mismatch" (suggest the user save a passphrase).
 */
object MessageDecryptor {

    sealed class Result {
        data class Success(
            val payload: SecurePayloadCodec.DecryptedPayload,
            /** Non-null when the message arrived under a contact's session key. */
            val sender: KeyExchange.Contact?,
        ) : Result()

        data class Failure(val reason: Reason, val message: String) : Result()
    }

    enum class Reason {
        UNKNOWN_FORMAT,        // not WTY1/2/3/4/5 at all
        SHARED_KEY_MISMATCH,   // tried passphrase, AEAD rejected
        SHARED_KEY_MISSING,    // PASSPHRASE-mode payload but no passphrase configured
        CONTACT_NOT_FOUND,     // SESSION_KEY mode and no contact matches
        NO_CONTACTS,           // SESSION_KEY mode but user has no contacts saved
        NO_IDENTITY,           // SESSION_KEY but the user hasn't generated an identity
        OTHER,
    }

    /** Upper bound on accepted ciphertext length — guards against huge-paste DoS, which
     *  the WTY5 path would otherwise re-allocate once per contact during trial decrypt. */
    private const val MAX_PAYLOAD_CHARS = 512 * 1024

    fun decrypt(context: Context, payload: String): Result {
        if (payload.length > MAX_PAYLOAD_CHARS) {
            return Result.Failure(Reason.UNKNOWN_FORMAT, "加密内容过大")
        }
        if (payload.startsWith(DoubleRatchet.PREFIX_V5)) {
            return decryptWithRatchet(context, payload)
        }
        if (!SecurePayloadCodec.isPayload(payload)) {
            return Result.Failure(Reason.UNKNOWN_FORMAT, "不是文图易加密内容")
        }
        return if (SecurePayloadCodec.peekKeyMode(payload) ==
            SecurePayloadCodec.KEY_MODE_SESSION_KEY) {
            decryptWithAnyContact(context, payload)
        } else {
            decryptWithSharedPassphrase(context, payload)
        }
    }

    /** WTY5 (Double Ratchet): trial-decrypt non-destructively against each contact. */
    private fun decryptWithRatchet(context: Context, payload: String): Result {
        val identity = try {
            KeyExchange.loadIdentity(context)
        } catch (e: Exception) {
            return Result.Failure(Reason.NO_IDENTITY, "身份密钥读取失败：${e.message}")
        } ?: return Result.Failure(Reason.NO_IDENTITY, "收到棘轮加密消息，但你还没生成身份码")

        val contacts = KeyExchange.listContacts(context)
        if (contacts.isEmpty()) return Result.Failure(Reason.NO_CONTACTS, "棘轮加密消息需要先扫码加好友")

        for (contact in contacts) {
            val plain = RatchetSession.tryDecrypt(context, identity, contact, payload) ?: continue
            return Result.Success(SecurePayloadCodec.textPayload(plain), contact)
        }
        return Result.Failure(Reason.CONTACT_NOT_FOUND,
            "试遍 ${contacts.size} 位联系人都无法解密 — 也许对方还没把你加为联系人，或消息损坏")
    }

    private fun decryptWithSharedPassphrase(context: Context, payload: String): Result {
        val passphrase = try {
            WentuyiSettings.getPassphrase(context)
        } catch (e: IllegalStateException) {
            return Result.Failure(Reason.SHARED_KEY_MISSING,
                e.message ?: "请先设置共享密钥")
        }
        return try {
            Result.Success(SecurePayloadCodec.decryptEnvelope(payload, passphrase), null)
        } catch (e: GeneralSecurityException) {
            Result.Failure(Reason.SHARED_KEY_MISMATCH,
                "共享密钥不匹配或消息损坏 (${e.message ?: "AEAD 校验失败"})")
        } catch (e: IllegalArgumentException) {
            // Base64.decode throws this on malformed envelopes. Surface as a clean
            // "unknown format" so users don't see "bad base-64" gibberish.
            Result.Failure(Reason.UNKNOWN_FORMAT,
                "加密内容格式错或已损坏")
        }
    }

    private fun decryptWithAnyContact(context: Context, payload: String): Result {
        val identity = try {
            KeyExchange.loadIdentity(context)
        } catch (e: Exception) {
            return Result.Failure(Reason.NO_IDENTITY, "身份密钥读取失败：${e.message}")
        } ?: return Result.Failure(Reason.NO_IDENTITY,
            "收到对方会话加密的消息，但你还没生成身份码")

        val contacts = KeyExchange.listContacts(context)
        if (contacts.isEmpty()) return Result.Failure(Reason.NO_CONTACTS,
            "会话加密消息需要先扫码加好友")

        var malformed = false
        for (contact in contacts) {
            val secret = try {
                KeyExchange.deriveSharedSecret(identity, contact.publicKey)
            } catch (e: Exception) { continue }  // unsafe/low-order pubkey → skip
            try {
                val decrypted = SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, secret)
                CryptoUtils.wipe(secret)
                return Result.Success(decrypted, contact)
            } catch (e: GeneralSecurityException) {
                CryptoUtils.wipe(secret)
                // wrong contact for this message; try the next one
            } catch (e: IllegalArgumentException) {
                CryptoUtils.wipe(secret)
                // Malformed envelope — no point trying other contacts. Remember and
                // surface a clean error after the loop instead of "CONTACT_NOT_FOUND".
                malformed = true
                break
            }
        }
        return if (malformed) {
            Result.Failure(Reason.UNKNOWN_FORMAT, "加密内容格式错或已损坏")
        } else {
            Result.Failure(Reason.CONTACT_NOT_FOUND,
                "试遍 ${contacts.size} 位联系人都无法解密 — 也许对方还没把你加为联系人，或消息内容损坏")
        }
    }
}
