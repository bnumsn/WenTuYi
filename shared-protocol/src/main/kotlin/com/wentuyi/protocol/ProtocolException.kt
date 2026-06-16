package com.wentuyi.protocol

/**
 * Stable, machine-readable failure codes for protocol operations whose errors are shown to
 * users. Platform shells (the Android app is Chinese-only) map [code] to a localized message;
 * the English [ProtocolException.message] is only a developer/desktop fallback.
 *
 * One exception type carrying a code — rather than a hierarchy of typed subclasses — keeps the
 * protocol API lean and lets callers switch on a stable enum instead of catching many classes.
 * It extends [IllegalArgumentException] so existing `catch (IllegalArgumentException)` / generic
 * handlers (e.g. MessageDecryptor) keep working unchanged.
 */
enum class ProtocolError {
    LOW_ORDER_KEY,
    NOT_A_BACKUP,
    BACKUP_CORRUPT,
    BACKUP_LENGTH,
    BACKUP_CRC_MISMATCH,
    BACKUP_KEY_MISMATCH,
    NOT_AN_IDENTITY_QR,
    IDENTITY_QR_INCOMPLETE,
    IDENTITY_QR_CORRUPT,
    IDENTITY_KEY_LENGTH,
}

class ProtocolException(
    val code: ProtocolError,
    message: String = code.name,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
