package com.wentuyi.app

import android.util.Base64
import com.wentuyi.protocol.DoubleRatchet as ProtoDR
import com.wentuyi.protocol.KeyExchange as ProtoKE
import org.json.JSONObject

/** The persisted ratchet state — the single shared type from :shared-protocol. */
typealias RatchetState = ProtoDR.State

/**
 * Android glue over the shared Double Ratchet. The whole ratchet algorithm (root/chain KDFs,
 * AEAD, skipped-key handling, header packing, replay/skip bounds) lives once in
 * :shared-protocol; this object only:
 *   • forwards the crypto, converting the app's [KeyExchange.Identity] at the boundary, and
 *   • (de)serializes [RatchetState] as JSON for [WentuyiSettings].
 *
 * The JSON schema is byte-for-byte the one shipped before the migration, so ratchet sessions
 * already persisted on a user's device keep decoding unchanged.
 */
object DoubleRatchet {
    const val PREFIX_V5 = ProtoDR.PREFIX_V5

    fun isInitiator(selfIdentityPub: ByteArray, peerIdentityPub: ByteArray): Boolean =
        ProtoDR.isInitiator(selfIdentityPub, peerIdentityPub)

    fun initialRootKey(selfIdentity: KeyExchange.Identity, peerIdentityPub: ByteArray): ByteArray =
        ProtoDR.initialRootKey(selfIdentity.toProto(), peerIdentityPub)

    fun initAlice(rootKey: ByteArray, peerIdentityPub: ByteArray): RatchetState =
        ProtoDR.initAlice(rootKey, peerIdentityPub)

    fun initBob(rootKey: ByteArray, selfIdentity: KeyExchange.Identity): RatchetState =
        ProtoDR.initBob(rootKey, selfIdentity.toProto())

    fun encrypt(state: RatchetState, plaintext: ByteArray): String = ProtoDR.encrypt(state, plaintext)

    fun decrypt(state: RatchetState, payload: String): ByteArray = ProtoDR.decrypt(state, payload)

    // ─── JSON persistence (schema frozen — must keep decoding pre-migration state) ──────

    fun serialize(s: RatchetState): String {
        val o = JSONObject()
        o.put("rk", b64(s.rk))
        o.put("dhsPub", b64(s.dhsPub))
        o.put("dhsPriv", b64(s.dhsPriv))
        o.put("dhr", s.dhr?.let { b64(it) } ?: JSONObject.NULL)
        o.put("cks", s.cks?.let { b64(it) } ?: JSONObject.NULL)
        o.put("ckr", s.ckr?.let { b64(it) } ?: JSONObject.NULL)
        o.put("ns", s.ns); o.put("nr", s.nr); o.put("pn", s.pn)
        val sk = JSONObject()
        for ((k, v) in s.skipped) sk.put(k, b64(v))
        o.put("skipped", sk)
        return o.toString()
    }

    fun deserialize(json: String): RatchetState {
        val o = JSONObject(json)
        val skipped = LinkedHashMap<String, ByteArray>()
        val sk = o.getJSONObject("skipped")
        for (k in sk.keys()) skipped[k] = unb64(sk.getString(k))
        return RatchetState(
            rk = unb64(o.getString("rk")),
            dhsPub = unb64(o.getString("dhsPub")),
            dhsPriv = unb64(o.getString("dhsPriv")),
            dhr = o.optString("dhr").takeIf { !o.isNull("dhr") }?.let { unb64(it) },
            cks = o.optString("cks").takeIf { !o.isNull("cks") }?.let { unb64(it) },
            ckr = o.optString("ckr").takeIf { !o.isNull("ckr") }?.let { unb64(it) },
            ns = o.getInt("ns"), nr = o.getInt("nr"), pn = o.getInt("pn"),
            skipped = skipped,
        )
    }

    private fun KeyExchange.Identity.toProto() = ProtoKE.Identity(publicKey, privateKey)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
