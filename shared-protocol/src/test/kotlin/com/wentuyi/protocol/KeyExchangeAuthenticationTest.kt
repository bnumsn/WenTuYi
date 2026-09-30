package com.wentuyi.protocol

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyExchangeAuthenticationTest {
    private fun identity(seed: Int): KeyExchange.Identity {
        val privateKey = ByteArray(32) { (seed + it).toByte() }
        val publicKey = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        return KeyExchange.Identity(publicKey, privateKey)
    }

    @Test
    fun fullCodeIsSymmetricAndPreservesAll256Bits() {
        val alice = identity(1)
        val bob = identity(65)
        val code = KeyExchange.shortAuthString(alice, bob.publicKey)

        assertEquals(2, KeyExchange.AUTH_VERSION)
        assertEquals(code, KeyExchange.shortAuthString(bob, alice.publicKey))
        assertTrue(Regex("[0-9A-F]{4}( [0-9A-F]{4}){15}").matches(code))
        assertEquals(64, code.replace(" ", "").length)
        // Independently calculated with Python cryptography X25519 + HKDF-SHA256.
        assertEquals(
            "1296 050B A2C1 F4C1 3A71 8367 8403 E446 E45F 955E C3D8 2D93 1E3D 8365 4CF6 EF69",
            code,
        )
    }

    @Test
    fun everyPublicKeyByteIsBoundEvenWhenX25519IgnoresItsHighBit() {
        val alice = identity(1)
        val bob = identity(65)
        val original = KeyExchange.shortAuthString(alice, bob.publicKey)
        for (i in 0 until 32) {
            val changedSelf = alice.publicKey.copyOf().apply { this[i] = (this[i].toInt() xor 1).toByte() }
            val changedPeer = bob.publicKey.copyOf().apply { this[i] = (this[i].toInt() xor 1).toByte() }
            assertNotEquals(original, KeyExchange.shortAuthString(alice, changedPeer), "peer public byte $i")
            assertNotEquals(
                original,
                KeyExchange.shortAuthString(KeyExchange.Identity(changedSelf, alice.privateKey), bob.publicKey),
                "self public byte $i",
            )
        }
        val changedAlicePub = alice.publicKey.copyOf().apply { this[31] = (this[31].toInt() xor 0x80).toByte() }
        val changedBobPub = bob.publicKey.copyOf().apply { this[31] = (this[31].toInt() xor 0x80).toByte() }

        // X25519 masks this bit, so binding only the DH result would miss these changes.
        assertContentEquals(
            KeyExchange.ecdh(alice.privateKey, bob.publicKey),
            KeyExchange.ecdh(alice.privateKey, changedBobPub),
        )
        assertNotEquals(original, KeyExchange.shortAuthString(alice, changedBobPub))
        assertNotEquals(
            original,
            KeyExchange.shortAuthString(KeyExchange.Identity(changedAlicePub, alice.privateKey), bob.publicKey),
        )
    }

    @Test
    fun replacingEitherIdentityChangesTheCode() {
        val alice = identity(1)
        val bob = identity(65)
        val replacement = identity(129)
        val original = KeyExchange.shortAuthString(alice, bob.publicKey)

        assertNotEquals(original, KeyExchange.shortAuthString(replacement, bob.publicKey))
        assertNotEquals(original, KeyExchange.shortAuthString(alice, replacement.publicKey))
        // Bind the DH secret as well as public input, even for inconsistent imported input.
        assertNotEquals(
            original,
            KeyExchange.shortAuthString(KeyExchange.Identity(alice.publicKey, replacement.privateKey), bob.publicKey),
        )
    }

    @Test
    fun authenticationCodeUsesANewDomainAndNeverExposesTheSessionKey() {
        val alice = identity(1)
        val bob = identity(65)
        val rawCode = KeyExchange.shortAuthString(alice, bob.publicKey).replace(" ", "")
        val ordered = listOf(alice.publicKey, bob.publicKey).sortedWith { a, b ->
            var result = 0
            for (i in a.indices) {
                result = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
                if (result != 0) break
            }
            result
        }
        val oldDomain = CryptoUtils.hkdfSha256(
            KeyExchange.ecdh(alice.privateKey, bob.publicKey),
            ordered[0] + ordered[1],
            "WTY-SAS-v1".toByteArray(Charsets.US_ASCII),
            32,
        )
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        assertNotEquals(hex(oldDomain), rawCode)
        assertNotEquals(hex(KeyExchange.deriveSharedSecret(alice, bob.publicKey)), rawCode)
    }
}
