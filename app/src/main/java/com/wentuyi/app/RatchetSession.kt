package com.wentuyi.app

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Glue between [DoubleRatchet], per-contact persistence ([WentuyiSettings]) and the
 * send/decrypt paths. Handles lazy session bootstrap, atomic state save after every
 * ratchet step, and the role rule that makes a serverless ratchet work:
 *
 *  - **Initiator** = the peer whose identity public key sorts lower. Only the initiator
 *    can *start* a session (they have the first sending chain).
 *  - The **responder** has no sending chain until they've decrypted the initiator's first
 *    WTY5 message (which sets up both chains). Until then [encryptText] returns null and
 *    the caller falls back to the WTY4 session-key path — still encrypted, just no PFS for
 *    those first message(s).
 *
 * Decrypt is non-destructive across contacts: [DoubleRatchet.decrypt] is transactional
 * (commits only after the AEAD tag verifies), so a wrong-contact guess throws and never
 * corrupts that contact's real session — no defensive clone needed.
 */
object RatchetSession {

    /**
     * Serializes the whole load → encrypt/decrypt → save transaction. The IME launches each
     * send on its own coroutine whose crypto runs on Dispatchers.Default, so two fast taps
     * could otherwise both load the same persisted state and derive the same message key/IV
     * (AES-GCM nonce reuse). A single lock is enough — sends/receives are human-paced, so
     * contention is negligible; per-fingerprint locking would only add complexity.
     */
    private val lock = Any()

    /** WTY5 payload for [contact], or null if the ratchet has no sending chain yet. */
    fun encryptText(
        context: Context,
        identity: KeyExchange.Identity,
        contact: KeyExchange.Contact,
        text: String,
    ): String? = synchronized(lock) {
        val state = loadOrBootstrapForSend(context, identity, contact) ?: return null
        if (state.cks == null) return null  // responder hasn't received the first message yet
        val payload = DoubleRatchet.encrypt(state, text.toByteArray(StandardCharsets.UTF_8))
        WentuyiSettings.saveRatchet(context, contact.fingerprint, DoubleRatchet.serialize(state))
        payload
    }

    /**
     * Non-destructive trial decrypt of a WTY5 [payload] as if from [contact]. Returns the
     * plaintext bytes and commits the advanced state on success, or null on any failure.
     */
    fun tryDecrypt(
        context: Context,
        identity: KeyExchange.Identity,
        contact: KeyExchange.Contact,
        payload: String,
    ): ByteArray? = synchronized(lock) {
        val state = loadOrBootstrapForReceive(context, identity, contact) ?: return null
        // decrypt() is transactional: on failure [state] is untouched, so no defensive clone
        // is needed — a wrong-contact attempt just throws and we move on without persisting.
        try {
            val plain = DoubleRatchet.decrypt(state, payload)
            WentuyiSettings.saveRatchet(context, contact.fingerprint, DoubleRatchet.serialize(state))
            plain
        } catch (e: Exception) {
            null  // wrong contact / not-for-us — state was not advanced
        }
    }

    private fun loadOrBootstrapForSend(
        context: Context, identity: KeyExchange.Identity, contact: KeyExchange.Contact,
    ): DoubleRatchet.State? {
        WentuyiSettings.loadRatchet(context, contact.fingerprint)
            ?.let { return DoubleRatchet.deserialize(it) }
        // No state yet: only the initiator can open a session.
        return if (DoubleRatchet.isInitiator(identity.publicKey, contact.publicKey))
            DoubleRatchet.initAlice(
                DoubleRatchet.initialRootKey(identity, contact.publicKey), contact.publicKey)
        else null
    }

    private fun loadOrBootstrapForReceive(
        context: Context, identity: KeyExchange.Identity, contact: KeyExchange.Contact,
    ): DoubleRatchet.State? {
        WentuyiSettings.loadRatchet(context, contact.fingerprint)
            ?.let { return DoubleRatchet.deserialize(it) }
        // No state yet: only the responder bootstraps on receive (initial ratchet key =
        // own identity). An initiator with no state can't have a first message to decrypt.
        return if (!DoubleRatchet.isInitiator(identity.publicKey, contact.publicKey))
            DoubleRatchet.initBob(
                DoubleRatchet.initialRootKey(identity, contact.publicKey), identity)
        else null
    }
}
