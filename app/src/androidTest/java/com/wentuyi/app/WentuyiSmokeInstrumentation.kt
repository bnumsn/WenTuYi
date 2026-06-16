package com.wentuyi.app

import com.wentuyi.protocol.SecurePayloadCodec

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.ByteArrayOutputStream

/**
 * Device-side smoke test covering the v3 cryptographic + transport pipeline:
 *
 *   1. SecurePayloadCodec round-trip (passphrase + session key) and tamper rejection.
 *   2. SecurePayloadCodec backward-compat: decrypt a v2 envelope produced inline.
 *   3. KeyExchange ECDH symmetry — Alice and Bob derive identical session keys.
 *   4. KeyExchange identity-QR encode/decode roundtrip + persisted contact.
 *   5. TextImageCodec: encryptedTextAsQr → readQrText → decrypt; JPEG re-compression
 *      tolerance (the property the v2 codec failed).
 *   6. ImageStore + ImageContentProvider readback.
 *   7. PinyinCandidates unchanged.
 *   8. KeyboardTestActivity commitContent path (debug only).
 */
class WentuyiSmokeInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val context = targetContext
            runAllChecks(context, results)
            results.putString("wentuyi", "smoke test passed")
            finish(Activity.RESULT_OK, results)
        } catch (t: Throwable) {
            results.putString("wentuyi", "smoke test failed")
            results.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun runAllChecks(context: Context, results: Bundle) {
        val source = "文图易 v3 设备端测试\nhello encrypted via QR + Argon2id"
        val passphrase = "wentuyi-smoke-key"

        // 0. Negative tests for the things v0.4 audit flagged.
        runNegativeChecks(context, passphrase)

        // 1. v4 payload round-trip + tamper.
        val payload = SecurePayloadCodec.encryptTextToPayload(source, passphrase)
        assertTrue(payload.startsWith(SecurePayloadCodec.PREFIX_V4), "v4 prefix")
        val decryptedText = SecurePayloadCodec.decryptPayload(payload, passphrase)
        assertEquals(source, decryptedText, "v4 text round-trip")
        assertDecryptFails(payload, "wrong-key", "v4 wrong key")
        // Header-bit flip: change the type byte → AAD verifies, should reject.
        val mangled = mangleHeader(payload)
        assertDecryptFails(mangled, passphrase, "v4 AAD tamper rejected")

        // 2. Pinyin smoke (Kotlin sees the unchanged Java helper).
        assertEquals("你好", PinyinCandidates.firstCandidateOrRaw("nihao"), "pinyin nihao")
        assertEquals("中文", PinyinCandidates.firstCandidateOrRaw("zhongwen"), "pinyin zhongwen")

        // 3. WentuyiSettings save/load + restore.
        val previous = runCatching { WentuyiSettings.getPassphrase(context) }.getOrNull()
        try {
            WentuyiSettings.setPassphrase(context, "wentuyi-settings-smoke-key")
            assertEquals(
                "wentuyi-settings-smoke-key",
                WentuyiSettings.getPassphrase(context),
                "settings passphrase round-trip"
            )
            if (WentuyiSettings.isUsingDefaultPassphrase(context))
                throw AssertionError("settings default flag")
        } finally {
            previous?.let { WentuyiSettings.setPassphrase(context, it) }
        }

        // 4. KeyExchange — ECDH symmetry on two ad-hoc identities.
        val alice = generateIdentityForTest()
        val bob = generateIdentityForTest()
        val aliceSecret = KeyExchange.deriveSharedSecret(alice, bob.publicKey)
        val bobSecret = KeyExchange.deriveSharedSecret(bob, alice.publicKey)
        assertArrayEquals(aliceSecret, bobSecret, "ECDH symmetry")
        // Identity QR encode/decode roundtrip.
        val qrText = KeyExchange.encodeIdentityForQr("alice", alice.publicKey)
        val (decodedName, decodedKey) = KeyExchange.decodeIdentityFromQr(qrText)
        assertEquals("alice", decodedName, "identity QR name")
        assertArrayEquals(alice.publicKey, decodedKey, "identity QR public key")
        // Session-key round-trip end-to-end.
        val sessionPayload = SecurePayloadCodec.encryptTextWithSessionKey(source, aliceSecret)
        val sessionDecrypted = SecurePayloadCodec.decryptEnvelopeWithSessionKey(sessionPayload, bobSecret)
        assertEquals(source, sessionDecrypted.text(), "session-key round-trip")

        // 5. ZXing QR — encode/decode round-trip with JPEG re-compression tolerance.
        val qrs = TextImageCodec.renderEncryptedTextAsQr(source, passphrase)
        assertTrue(qrs.isNotEmpty(), "encrypted QR rendered")
        val readback = TextImageCodec.assembleEncryptedPayload(qrs)
        assertEquals(source, SecurePayloadCodec.decryptPayload(readback, passphrase), "QR round-trip")
        // JPEG re-encode at q=80 (typical IM compression) should still decode.
        val jpegBitmaps = qrs.map { jpegRoundTrip(it, 80) }
        val jpegReadback = TextImageCodec.assembleEncryptedPayload(jpegBitmaps)
        assertEquals(
            source,
            SecurePayloadCodec.decryptPayload(jpegReadback, passphrase),
            "QR round-trip after JPEG q=80"
        )
        results.putInt("qr_pages", qrs.size)
        results.putInt("first_qr_width", qrs[0].width)
        results.putInt("first_qr_height", qrs[0].height)

        // 6. Long text → multi-QR path.
        val longText = buildString {
            for (i in 1..36) {
                if (i > 1) append('\n')
                append("第 $i 行：文图易 v3 / Argon2id / QR code / 多段 Structured-style 拆分。")
            }
        }
        val longQrs = TextImageCodec.renderEncryptedTextAsQr(longText, passphrase)
        results.putInt("long_qr_render_count", longQrs.size)
        results.putInt("long_qr_first_w", longQrs[0].width)
        results.putInt("long_qr_first_h", longQrs[0].height)
        assertTrue(longQrs.size >= 2, "long text produces multiple QRs (got ${longQrs.size})")
        // Try decoding each QR individually first so a failure pinpoints which page.
        for ((idx, bm) in longQrs.withIndex()) {
            try { TextImageCodec.readQrText(bm) } catch (e: Exception) {
                results.putString("long_qr_fail_page", "${idx + 1}/${longQrs.size}: ${e.message}")
                throw AssertionError("long QR page ${idx + 1}/${longQrs.size} unreadable: ${e.message}")
            }
        }
        val longReadback = TextImageCodec.assembleEncryptedPayload(longQrs)
        assertEquals(longText, SecurePayloadCodec.decryptPayload(longReadback, passphrase), "multi-QR reassemble")
        results.putInt("long_qr_pages", longQrs.size)

        // 7. Plain text image still renders.
        val plain = TextImageCodec.renderPlainTextImage(source)
        if (plain.width <= 0 || plain.height <= 0) throw AssertionError("plain bitmap size")
        results.putInt("plain_width", plain.width)
        results.putInt("plain_height", plain.height)

        // 8. Identity QR end-to-end.
        val identity = KeyExchange.getOrCreateIdentity(context)
        val identityBitmap = TextImageCodec.renderIdentityQr(identity, "smoke-tester")
        val (idName, idKey) = TextImageCodec.readIdentityQr(identityBitmap)
        assertEquals("smoke-tester", idName, "identity QR readback name")
        assertArrayEquals(identity.publicKey, idKey, "identity QR readback key")

        // 9. ImageStore + ImageContentProvider readable from any process.
        val storedUri: Uri = ImageStore.savePng(context, qrs[0])
        context.contentResolver.openInputStream(storedUri).use { input ->
            if (input == null || input.read() < 0) throw AssertionError("provider image read")
        }

        // 10. KeyboardTestActivity commitContent (debug only).
        assertKeyboardTestActivityAcceptsImage(context, plain)
    }

    /**
     * Regression checks for the audit-driven fixes:
     *   • X25519 ecdh must reject the all-zero point (low-order public key).
     *   • Multi-QR (WTYP1) assembly must reject missing pages, duplicate ids, etc.
     *   • Settings v3 must transparently read a KS1: prefs payload and rewrite it as KS2:.
     *   • Generic v2 (WTY2:) envelope round-trip still works through the legacy path.
     */
    private fun runNegativeChecks(context: Context, passphrase: String) {
        // 0a. Low-order X25519 public key: every byte zero → must throw.
        val alice = generateIdentityForTest()
        val zeroPub = ByteArray(32)
        var thrown = false
        try { KeyExchange.ecdh(alice.privateKey, zeroPub) }
        catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown, "X25519 low-order pubkey must be rejected")

        // 0b. WTYP1 reassembly negatives.
        val midChunk = "${TextImageCodec.MULTI_PREFIX}|abc|2|3|XYZ"
        val lastChunk = "${TextImageCodec.MULTI_PREFIX}|abc|3|3|XYZ"
        // Missing first page:
        var asmThrown = false
        try { TextImageCodec.assemblePayloadFromTexts(listOf(midChunk, lastChunk)) }
        catch (e: IllegalArgumentException) { asmThrown = true }
        assertTrue(asmThrown, "WTYP1 missing-page must throw")
        // Mismatched chunk id:
        val mismatched = "${TextImageCodec.MULTI_PREFIX}|xyz|1|3|XYZ"
        asmThrown = false
        try { TextImageCodec.assemblePayloadFromTexts(listOf(mismatched, midChunk, lastChunk)) }
        catch (e: IllegalArgumentException) { asmThrown = true }
        assertTrue(asmThrown, "WTYP1 mismatched chunk id must throw")
        // 0b'. DoS resistance: a hostile chunk advertising total > MAX_QR_PAGES must be
        // rejected before assembly allocates a huge "missing pages" walk.
        val hostileTotal = "${TextImageCodec.MULTI_PREFIX}|h0s|1|999|XYZ"
        asmThrown = false
        try { TextImageCodec.assemblePayloadFromTexts(listOf(hostileTotal)) }
        catch (e: IllegalArgumentException) { asmThrown = true }
        assertTrue(asmThrown, "WTYP1 hostile total must be rejected")

        // 0c. v2 envelope: construct one inline so the test doesn't depend on a checked-in fixture.
        val v2 = buildV2Envelope("legacy round-trip", passphrase)
        val decryptedV2 = SecurePayloadCodec.decryptEnvelope(v2, passphrase)
        assertEquals("legacy round-trip", decryptedV2.text(), "v2 round-trip via decryptEnvelope")

        // 0d. KS1 → KS2 auto-migration. Write a KS1-prefixed value directly and ensure
        // the next read silently migrates it to KS2:.
        val prefs = context.applicationContext.getSharedPreferences("wentuyi_settings",
            Context.MODE_PRIVATE)
        val previousPassphrase = runCatching { WentuyiSettings.getPassphrase(context) }.getOrNull()
        try {
            // Seed a KS1: value by encrypting and patching the prefix.
            WentuyiSettings.setPassphrase(context, "ks-migration-canary")
            val original = prefs.getString("passphrase_encrypted", null)
                ?: throw AssertionError("KS migration seed: missing pref")
            prefs.edit()
                .putString("passphrase_encrypted", "KS1:" + original.removePrefix("KS2:"))
                .apply()
            // Reading should succeed AND auto-rewrite to KS2:.
            assertEquals("ks-migration-canary", WentuyiSettings.getPassphrase(context),
                "KS1 read")
            val after = prefs.getString("passphrase_encrypted", null) ?: ""
            assertTrue(after.startsWith("KS2:"), "KS1 → KS2 migration must rewrite prefix")
        } finally {
            previousPassphrase?.let { WentuyiSettings.setPassphrase(context, it) }
        }
    }

    /**
     * Constructs a v2 (`WTY2:`) envelope without exposing the encryption path:
     *   AES-256-GCM, 16B salt + 12B IV + PBKDF2-HmacSHA256 / 120 000 iters, no AAD.
     */
    private fun buildV2Envelope(text: String, passphrase: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
            javax.crypto.spec.GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(2 + salt.size + iv.size + ct.size)
        packed[0] = 0x02
        packed[1] = SecurePayloadCodec.TYPE_TEXT.toByte()
        System.arraycopy(salt, 0, packed, 2, salt.size)
        System.arraycopy(iv, 0, packed, 2 + salt.size, iv.size)
        System.arraycopy(ct, 0, packed, 2 + salt.size + iv.size, ct.size)
        return SecurePayloadCodec.PREFIX_V2 + android.util.Base64.encodeToString(packed,
            android.util.Base64.NO_WRAP)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun generateIdentityForTest(): KeyExchange.Identity {
        // Generate without persisting — reach into KeyExchange via its public API.
        // Using getOrCreateIdentity on a fresh in-memory pair would need context tricks;
        // instead, do an ECDH against a freshly-generated peer keypair using the same
        // primitives exposed by KeyExchange (it owns the X25519 gen path).
        val identity = KeyExchange.let {
            // Round-trip the public/private pair through replaceIdentity is overkill
            // for a per-test pair; use the BC generator directly.
            val gen = org.bouncycastle.crypto.generators.X25519KeyPairGenerator()
            gen.init(org.bouncycastle.crypto.params.X25519KeyGenerationParameters(java.security.SecureRandom()))
            val pair = gen.generateKeyPair()
            val priv = (pair.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters).encoded
            val pub = (pair.public as org.bouncycastle.crypto.params.X25519PublicKeyParameters).encoded
            KeyExchange.Identity(pub, priv)
        }
        return identity
    }

    private fun mangleHeader(payload: String): String {
        // Header byte 1 = type (same offset in v3/v4). Flip it and re-pack. AAD must reject.
        require(payload.startsWith(SecurePayloadCodec.PREFIX_V4))
        val body = android.util.Base64.decode(
            payload.substring(SecurePayloadCodec.PREFIX_V4.length),
            android.util.Base64.NO_WRAP
        )
        body[1] = (body[1].toInt() xor 0xFF).toByte()
        return SecurePayloadCodec.PREFIX_V4 +
            android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)
    }

    private fun jpegRoundTrip(bitmap: Bitmap, quality: Int): Bitmap {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw AssertionError("jpeg encode")
        val bytes = out.toByteArray()
        // Force ARGB_8888 — recent OEM/Pixel decoders may default to HARDWARE config,
        // which makes getPixels() in ZXing's RGBLuminanceSource return zeroes.
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw AssertionError("jpeg decode")
    }

    private fun assertEquals(expected: String, actual: String, label: String) {
        if (expected != actual) throw AssertionError("$label: expected '$expected' but got '$actual'")
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray, label: String) {
        if (!expected.contentEquals(actual))
            throw AssertionError("$label: expected ${expected.size} bytes but got ${actual.size}")
    }

    private fun assertTrue(condition: Boolean, label: String) {
        if (!condition) throw AssertionError(label)
    }

    private fun assertDecryptFails(payload: String, passphrase: String, label: String) {
        try {
            SecurePayloadCodec.decryptPayload(payload, passphrase)
            throw AssertionError("$label: expected failure")
        } catch (expected: Exception) { /* AES-GCM authentication should reject */ }
    }

    private fun assertKeyboardTestActivityAcceptsImage(context: Context, bitmap: Bitmap) {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 25) return
        val uri = ImageStore.savePng(context, bitmap)
        val intent = Intent(context, KeyboardTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = startActivitySync(intent)
        val testActivity = activity as? KeyboardTestActivity
            ?: throw AssertionError("keyboard test activity launch")
        val committed = booleanArrayOf(false)
        val status = arrayOf<String?>(null)
        runOnMainSync {
            committed[0] = testActivity.commitImageForTest(uri)
            status[0] = testActivity.currentStatusForTest()
        }
        testActivity.finish()
        if (!committed[0]) throw AssertionError("keyboard test image commit")
        if (status[0]?.startsWith("已接收图片：") != true)
            throw AssertionError("keyboard test image status: ${status[0]}")
    }
}
