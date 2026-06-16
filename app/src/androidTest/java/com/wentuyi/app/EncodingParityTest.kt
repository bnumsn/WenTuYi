package com.wentuyi.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wentuyi.protocol.Encoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Device half of the Base64 migration proof: shows :shared-protocol's pure-Kotlin [Encoding]
 * produces byte-identical output to `android.util.Base64` with the exact flags the app uses
 * today (`NO_WRAP` for payloads, `NO_WRAP or URL_SAFE` for identity/contacts). Together with
 * :shared-protocol's EncodingBase64Test (Encoding == java.util.Base64), this confirms swapping
 * app call sites onto Encoding preserves every stored contact, identity QR and on-wire payload.
 *
 * Runs on a real device so it also proves Encoding loads and works at the app's runtime — the
 * whole reason we replaced java.util.Base64, which is absent below API 26.
 */
@RunWith(AndroidJUnit4::class)
class EncodingParityTest {

    @Test fun standardEncodingMatchesAndroidNoWrap() {
        for (len in 0..200) {
            val bytes = Random(len.toLong()).nextBytes(len)
            assertEquals(
                "encode len=$len",
                Base64.encodeToString(bytes, Base64.NO_WRAP),
                Encoding.b64(bytes),
            )
            assertArrayEquals(
                "decode len=$len",
                Base64.decode(Encoding.b64(bytes), Base64.NO_WRAP),
                Encoding.b64Decode(Encoding.b64(bytes)),
            )
        }
    }

    @Test fun urlSafeEncodingMatchesAndroidUrlSafe() {
        val flags = Base64.NO_WRAP or Base64.URL_SAFE
        for (len in 0..200) {
            val bytes = Random(2000L + len).nextBytes(len)
            assertEquals(
                "url encode len=$len",
                Base64.encodeToString(bytes, flags),
                Encoding.b64Url(bytes),
            )
            assertArrayEquals(
                "url decode len=$len",
                Base64.decode(Encoding.b64Url(bytes), flags),
                Encoding.b64UrlDecode(Encoding.b64Url(bytes)),
            )
        }
    }

    /** Cross-decode: data the app encoded with android.util.Base64 must decode via Encoding. */
    @Test fun encodingDecodesAndroidProducedData() {
        for (len in 1..200) {
            val bytes = Random(4000L + len).nextBytes(len)
            val androidStd = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val androidUrl = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            assertArrayEquals("std len=$len", bytes, Encoding.b64Decode(androidStd))
            assertArrayEquals("url len=$len", bytes, Encoding.b64UrlDecode(androidUrl))
        }
    }
}
