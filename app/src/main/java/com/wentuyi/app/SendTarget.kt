package com.wentuyi.app

/**
 * Who a message is being encrypted *for*. Top-level rather than nested in [SendController]
 * because the IME is no longer the only sender — [EncryptActivity] resolves the same target
 * for text arriving from the share sheet, the clipboard or a text-selection menu, and both
 * paths must obey the same fail-closed rule.
 */
sealed class SendTarget {
    object SharedPassphrase : SendTarget()

    data class Contact(
        val contact: KeyExchange.Contact,
        val identity: KeyExchange.Identity,
    ) : SendTarget()

    /**
     * Target resolution failed (selected contact vanished, identity key unreadable, …).
     * The send is refused with [reason] — we must **never** silently fall back to the
     * shared passphrase, or a message the user thinks is going to a verified contact
     * could become readable by everyone who knows the old shared key.
     */
    data class Unavailable(val reason: String) : SendTarget()
}
