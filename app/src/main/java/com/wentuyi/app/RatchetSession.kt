package com.wentuyi.app

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Glue between [DoubleRatchet], per-contact persistence ([WentuyiSettings]) and the
 * send/decrypt paths. Handles lazy session bootstrap, atomic state save after every
 * ratchet step, and the epoch rule that makes a serverless ratchet survive a peer
 * losing its state.
 *
 * **Epochs.** Every session is stamped with the epoch that seeded its root key, and the
 * epoch travels in every WTY5 header. Before epochs existed, a peer who reinstalled (or
 * cleared data, or restored from their backup code) restarted from the same deterministic
 * root key while the other side's root key had already advanced — from then on *every*
 * message in *both* directions failed the AEAD, permanently, and neither side could tell
 * that from "corrupt message". The only escape was for both users to delete and re-add
 * each other at the same time, which nothing in the UI told them to do.
 *
 * With epochs the recovery is automatic in the common direction and one tap in the other:
 *  - **Peer reset and sends first** → their header carries a strictly newer epoch, we
 *    re-bootstrap as that epoch's receiver and decrypt it ([tryDecrypt] path B).
 *  - **We reset** → we mint a new epoch on our next send, and the peer's path B adopts it.
 *  - **We reset mid-conversation and they send first** → their epoch is *older* than
 *    nothing (we hold no state), so path B still adopts it; if their chain has already
 *    ratcheted past our identity key the AEAD fails and [Attempt.OutOfSync] tells the UI
 *    to offer [restart], which mints a fresh epoch they will in turn adopt.
 *
 * A *stale* epoch is refused rather than adopted, so a retired session's ciphertext can't
 * be replayed back into a live one.
 *
 * Decrypt is non-destructive across contacts: [DoubleRatchet.decrypt] is transactional
 * (commits only after the AEAD tag verifies), so a wrong-contact guess throws and never
 * corrupts that contact's real session — no defensive clone needed. Nothing is persisted
 * on a failed attempt, including a speculative path-B bootstrap.
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

    /** Outcome of trial-decrypting one WTY5 payload as if it came from one contact. */
    sealed class Attempt {
        class Ok(val plaintext: ByteArray) : Attempt()

        /** Wrong contact, corrupt ciphertext, or a message we simply can't be the peer of. */
        object NotForUs : Attempt()

        /**
         * The payload is plausibly from this contact but names a session we can't adopt —
         * either a retired (older) epoch, or a current-epoch chain we've fallen off.
         * The UI should offer [restart].
         */
        object OutOfSync : Attempt()
    }

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
     * Drops any existing session with [contact] and opens a fresh epoch we are the sender
     * of. This is the manual escape hatch for "we lost our state mid-conversation": the
     * peer adopts the new epoch the moment they receive our next message, no coordination
     * needed. Everything still in flight under the old epoch becomes undecryptable — that
     * is the point, and the caller must say so before invoking this.
     */
    fun restart(
        context: Context,
        identity: KeyExchange.Identity,
        contact: KeyExchange.Contact,
    ): Unit = synchronized(lock) {
        val epoch = DoubleRatchet.newEpoch()
        val state = DoubleRatchet.initSender(
            DoubleRatchet.initialRootKey(identity, contact.publicKey, epoch),
            contact.publicKey,
            epoch,
        )
        WentuyiSettings.saveRatchet(context, contact.fingerprint, DoubleRatchet.serialize(state))
    }

    /**
     * Non-destructive trial decrypt of a WTY5 [payload] as if from [contact]. On success the
     * advanced state is committed and persisted; on any failure nothing is written.
     */
    fun tryDecrypt(
        context: Context,
        identity: KeyExchange.Identity,
        contact: KeyExchange.Contact,
        payload: String,
    ): Attempt = synchronized(lock) {
        val headerEpoch = DoubleRatchet.peekEpoch(payload) ?: return Attempt.NotForUs
        val stored = WentuyiSettings.loadRatchet(context, contact.fingerprint)
            ?.let { runCatching { DoubleRatchet.deserialize(it) }.getOrNull() }

        // Path A — we hold this exact session. decrypt() is transactional, so a wrong-contact
        // guess leaves `stored` untouched and we just report NotForUs.
        if (stored != null && stored.epoch == headerEpoch) {
            return try {
                val plain = DoubleRatchet.decrypt(stored, payload)
                WentuyiSettings.saveRatchet(
                    context, contact.fingerprint, DoubleRatchet.serialize(stored))
                Attempt.Ok(plain)
            } catch (e: Exception) {
                Attempt.NotForUs
            }
        }

        // A retired epoch: refuse rather than re-bootstrap, so ciphertext from a session the
        // user has already moved past can't be replayed into a live one.
        if (stored != null && headerEpoch <= stored.epoch) return Attempt.OutOfSync

        // Path B — an epoch we don't hold and that is newer than anything we do. Either the
        // peer reset, or this is our first message from them. Bootstrap as that epoch's
        // receiver speculatively; only a verifying AEAD tag makes it real.
        val fresh = try {
            DoubleRatchet.initReceiver(
                DoubleRatchet.initialRootKey(identity, contact.publicKey, headerEpoch),
                identity,
                headerEpoch,
            )
        } catch (e: Exception) {
            return Attempt.NotForUs  // unusable peer key (low-order, wrong length, …)
        }
        return try {
            val plain = DoubleRatchet.decrypt(fresh, payload)
            WentuyiSettings.saveRatchet(
                context, contact.fingerprint, DoubleRatchet.serialize(fresh))
            Attempt.Ok(plain)
        } catch (e: Exception) {
            // Couldn't adopt it. If we hold *some* session with this contact the payload was
            // plausibly theirs and we've fallen off the chain (we reset mid-conversation);
            // with no state at all it's far more likely simply not from this contact.
            if (stored != null) Attempt.OutOfSync else Attempt.NotForUs
        }
    }

    private fun loadOrBootstrapForSend(
        context: Context, identity: KeyExchange.Identity, contact: KeyExchange.Contact,
    ): RatchetState? {
        WentuyiSettings.loadRatchet(context, contact.fingerprint)
            ?.let { return DoubleRatchet.deserialize(it) }
        // No state yet. Roles are per-epoch, but for the *first* epoch on a fresh contact we
        // still defer to public-key order: if both peers add each other and send at the same
        // moment, only one opens an epoch, so neither side's opening message is stranded
        // under an epoch the other has already superseded.
        return if (DoubleRatchet.isInitiator(identity.publicKey, contact.publicKey)) {
            val epoch = DoubleRatchet.newEpoch()
            DoubleRatchet.initSender(
                DoubleRatchet.initialRootKey(identity, contact.publicKey, epoch),
                contact.publicKey,
                epoch,
            )
        } else null
    }
}
