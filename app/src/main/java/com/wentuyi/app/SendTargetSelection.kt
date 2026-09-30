package com.wentuyi.app

/** Keep the selected identity even if the contact list is reordered or the peer disappears. */
class SendTargetSelection {
    var fingerprint: String? = null
        private set

    val isShared: Boolean get() = fingerprint == null

    fun select(fingerprint: String?) { this.fingerprint = fingerprint }

    fun contact(contacts: List<KeyExchange.Contact>): KeyExchange.Contact? =
        contacts.firstOrNull { it.fingerprint == fingerprint }

    /** -1 means the previous selection is unavailable; it must not select another peer. */
    fun indexIn(contacts: List<KeyExchange.Contact>): Int =
        if (isShared) 0 else contacts.indexOfFirst { it.fingerprint == fingerprint }
            .let { if (it < 0) -1 else it + 1 }
}
