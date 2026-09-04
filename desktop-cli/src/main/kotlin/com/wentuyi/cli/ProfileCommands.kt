package com.wentuyi.cli

import com.wentuyi.protocol.CryptoUtils
import com.wentuyi.protocol.DoubleRatchet
import com.wentuyi.protocol.Encoding
import com.wentuyi.protocol.KeyExchange
import com.wentuyi.protocol.SecurePayloadCodec

/**
 * Profile-aware `send` / `receive`, the desktop counterparts of the Android app's
 * [MessageEncryptor]/[MessageDecryptor] routing.
 *
 * The stateless commands (`encrypt-text`, `session-encrypt`, `ratchet-encrypt`, …) each do
 * exactly one protocol and require the caller to know which one applies. That is why every
 * Linux/Windows bridge only ever called `encrypt-text`: choosing between shared passphrase,
 * WTY4 session key and the WTY5 ratchet — and keeping a ratchet state file per peer —
 * is not something a shell wrapper should be doing. These two commands make that choice,
 * so the bridges pass text through and nothing else.
 *
 * The rules are deliberately the same ones the Android app follows:
 *  - **Send** prefers the WTY5 ratchet; it drops to the WTY4 session key only when no
 *    sending chain exists yet, and says so on stderr rather than downgrading quietly.
 *  - **Receive** trial-decrypts against every peer without committing anything until the
 *    AEAD verifies, adopts a strictly newer ratchet epoch (the peer reinstalled) and
 *    refuses an older one (replay of a retired session).
 */
object ProfileCommands {

    fun send(profile: Profile, peerName: String?, text: String) {
        require(text.isNotEmpty()) { "nothing to send" }
        if (peerName == null) {
            val passphrase = profile.passphrase()
                ?: throw IllegalStateException(
                    "no shared passphrase (set WENTUYI_PASSPHRASE, or run: desktop-cli set-passphrase)")
            println(SecurePayloadCodec.encryptTextToPayload(text, passphrase))
            return
        }

        val identity = profile.loadIdentity()
        val peer = profile.peerPublicKey(peerName)

        // Roles are per epoch, but the *first* epoch on a fresh peer still defers to public
        // key order, so two sides opening one simultaneously don't strand each other's
        // opening message. Matches RatchetSession.loadOrBootstrapForSend on Android.
        var state = profile.loadRatchet(peerName)
        if (state == null && DoubleRatchet.isInitiator(identity.publicKey, peer)) {
            val epoch = DoubleRatchet.newEpoch()
            state = DoubleRatchet.initSender(
                DoubleRatchet.initialRootKey(identity, peer, epoch), peer, epoch)
        }

        if (state?.cks != null) {
            println(DoubleRatchet.encrypt(state, text.toByteArray(Charsets.UTF_8)))
            profile.saveRatchet(peerName, state)
            return
        }

        // No sending chain yet: we are the responder and have not received their first
        // message. Still encrypted, just without forward secrecy for this one.
        System.err.println(
            "warning: no ratchet sending chain with '$peerName' yet — this message uses the " +
                "WTY4 session key and has no forward secrecy. It gains PFS once they reply.")
        val secret = KeyExchange.deriveSharedSecret(identity, peer)
        try {
            println(SecurePayloadCodec.encryptTextWithSessionKey(text, secret))
        } finally {
            CryptoUtils.wipe(secret)
        }
    }

    fun receive(profile: Profile, payload: String) {
        if (payload.startsWith(DoubleRatchet.PREFIX_V5)) {
            receiveRatchet(profile, payload)
            return
        }
        if (!SecurePayloadCodec.isPayload(payload)) {
            throw IllegalArgumentException("not a Wentuyi payload")
        }
        if (SecurePayloadCodec.peekKeyMode(payload) == SecurePayloadCodec.KEY_MODE_SESSION_KEY) {
            receiveSessionKey(profile, payload)
            return
        }
        val passphrase = profile.passphrase()
            ?: throw IllegalStateException(
                "this is a shared-key payload but no passphrase is configured " +
                    "(set WENTUYI_PASSPHRASE, or run: desktop-cli set-passphrase)")
        println(SecurePayloadCodec.decryptPayload(payload, passphrase))
    }

    private fun receiveRatchet(profile: Profile, payload: String) {
        val peers = profile.peerNames()
        if (peers.isEmpty()) throw IllegalStateException("ratchet message but no peers configured")
        val identity = profile.loadIdentity()
        val headerEpoch = DoubleRatchet.peekEpoch(payload)
            ?: throw IllegalArgumentException("malformed WTY5 payload")

        var desynced: String? = null
        for (name in peers) {
            val peer = runCatching { profile.peerPublicKey(name) }.getOrNull() ?: continue
            val stored = profile.loadRatchet(name)

            // Path A — we hold exactly this session.
            if (stored != null && stored.epoch == headerEpoch) {
                val plain = runCatching { DoubleRatchet.decrypt(stored, payload) }.getOrNull()
                if (plain != null) {
                    profile.saveRatchet(name, stored)
                    emit(name, plain)
                    return
                }
                continue
            }
            // A retired epoch: refuse rather than re-bootstrap, so a dead session's
            // ciphertext can't be replayed into the live one.
            if (stored != null && headerEpoch <= stored.epoch) {
                if (desynced == null) desynced = name
                continue
            }
            // Path B — an epoch newer than anything we hold. Either they reset, or this is
            // their first message. Bootstrap speculatively; only a valid tag makes it real.
            val fresh = runCatching {
                DoubleRatchet.initReceiver(
                    DoubleRatchet.initialRootKey(identity, peer, headerEpoch), identity, headerEpoch)
            }.getOrNull() ?: continue
            val plain = runCatching { DoubleRatchet.decrypt(fresh, payload) }.getOrNull()
            if (plain != null) {
                profile.saveRatchet(name, fresh)
                emit(name, plain)
                return
            }
            if (stored != null && desynced == null) desynced = name
        }

        if (desynced != null) {
            throw IllegalStateException(
                "ratchet session with '$desynced' is out of sync (either side reinstalled or " +
                    "cleared data). Run: desktop-cli peer-reset --peer $desynced, then send " +
                    "them one message — they adopt the new session automatically.")
        }
        throw IllegalStateException("no configured peer could decrypt this ratchet message")
    }

    private fun receiveSessionKey(profile: Profile, payload: String) {
        val peers = profile.peerNames()
        if (peers.isEmpty()) throw IllegalStateException("session-key message but no peers configured")
        val identity = profile.loadIdentity()
        for (name in peers) {
            val peer = runCatching { profile.peerPublicKey(name) }.getOrNull() ?: continue
            val secret = runCatching { KeyExchange.deriveSharedSecret(identity, peer) }.getOrNull()
                ?: continue
            try {
                val decrypted = runCatching {
                    SecurePayloadCodec.decryptEnvelopeWithSessionKey(payload, secret)
                }.getOrNull()
                if (decrypted != null) {
                    emit(name, decrypted.data)
                    return
                }
            } finally {
                CryptoUtils.wipe(secret)
            }
        }
        throw IllegalStateException("no configured peer could decrypt this message")
    }

    /** Plaintext on stdout, provenance on stderr — so pipelines get only the message. */
    private fun emit(peerName: String, plain: ByteArray) {
        System.err.println("from: $peerName")
        println(String(plain, Charsets.UTF_8))
    }

    // ─── Profile management ───────────────────────────────────────────────────

    fun init(profile: Profile) {
        val identity = profile.createIdentity()
        println("home=${profile.home}")
        println("fingerprint=${identity.fingerprint}")
        println("publicKey=${Encoding.b64Url(identity.publicKey)}")
        println("identityQr=${KeyExchange.encodeIdentityForQr("文图易桌面", identity.publicKey)}")
        System.err.println(
            "The identity file under ${profile.home} IS your private key, stored in the clear " +
                "(there is no Keystore on desktop) — it is written 0600; back it up offline.")
    }

    fun whoami(profile: Profile) {
        val identity = profile.loadIdentity()
        println("home=${profile.home}")
        println("fingerprint=${identity.fingerprint}")
        println("publicKey=${Encoding.b64Url(identity.publicKey)}")
        println("identityQr=${KeyExchange.encodeIdentityForQr("文图易桌面", identity.publicKey)}")
        println("passphrase=${if (profile.passphrase() != null) "set" else "not set"}")
    }

    fun peerList(profile: Profile) {
        val identity = runCatching { profile.loadIdentity() }.getOrNull()
        for (name in profile.peerNames()) {
            val pub = runCatching { profile.peerPublicKey(name) }.getOrNull() ?: continue
            val sas = identity?.let {
                runCatching { KeyExchange.shortAuthString(it, pub) }.getOrNull()
            } ?: "?"
            val ratchet = profile.loadRatchet(name)
            val session = if (ratchet == null) "none" else "epoch ${ratchet.epoch}"
            println("$name\tsas=$sas\tratchet=$session")
        }
    }
}
