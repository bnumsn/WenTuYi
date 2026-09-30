package com.wentuyi.app

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("wentuyi_settings", Context.MODE_PRIVATE)
    private lateinit var saved: Map<String, *>

    @Before fun savePreferences() { saved = prefs.all.toMap() }

    @After fun restorePreferences() {
        val editor = prefs.edit().clear()
        for ((key, value) in saved) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
        check(editor.commit())
    }

    @Test fun oldEncryptedVerificationRequiresComparingTheFullSafetyCode() {
        val me = KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val row = JSONObject().put("name", "old-verification")
            .put("publicKey", android.util.Base64.encodeToString(peer.publicKey,
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE))
            .put("verified", true)
        WentuyiSettings.setContactsJson(context, JSONArray().put(row).toString())
        val migrated = KeyExchange.listContacts(context).single()
        assertFalse(migrated.verified)
        assertFalse(JSONArray(WentuyiSettings.getContactsJson(context)).getJSONObject(0).optBoolean("verified"))
        val code = KeyExchange.shortAuthString(me, peer.publicKey)
        KeyExchange.setContactVerified(context, migrated.fingerprint, true, code)
        assertTrue(KeyExchange.listContacts(context).single().verified)
        assertEquals(KeyExchange.AUTH_VERSION, KeyExchange.listContacts(context).single().authVersion)
        assertEquals(KeyExchange.AUTH_VERSION,
            JSONArray(WentuyiSettings.getContactsJson(context)).getJSONObject(0).getInt("authVersion"))
    }

    @Test fun anOpenVerificationDialogCannotVerifyAfterTheIdentityChanges() {
        val me = KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val contact = KeyExchange.Contact("stale-dialog", peer.publicKey)
        KeyExchange.saveContact(context, contact)
        val oldCode = KeyExchange.shortAuthString(me, peer.publicKey)
        KeyExchange.replaceIdentity(context)
        expectFailure { KeyExchange.setContactVerified(context, contact.fingerprint, true, oldCode) }
        assertFalse(KeyExchange.findContact(context, contact.fingerprint)!!.verified)
    }

    @Test fun unreadableIdentityIsNotSilentlyReplaced() {
        prefs.edit().putString("identity_encrypted", "KS2:AAAA").commit()
        expectFailure { KeyExchange.getOrCreateIdentity(context) }
        assertEquals("KS2:AAAA", prefs.getString("identity_encrypted", null))
    }

    @Test fun staleContactEditsCannotRestoreRevokedVerification() {
        KeyExchange.getOrCreateIdentity(context)
        val peer = KeyExchange.generateIdentity()
        val contact = KeyExchange.Contact("before", peer.publicKey, verified = true)
        KeyExchange.saveContact(context, contact)
        KeyExchange.setContactVerified(context, contact.fingerprint, false)
        KeyExchange.saveContact(context, contact.copy(name = "stale-save"))
        assertFalse(KeyExchange.findContact(context, contact.fingerprint)!!.verified)
        KeyExchange.renameContact(context, peer.publicKey, "renamed")
        val saved = KeyExchange.findContact(context, contact.fingerprint)!!
        assertEquals("renamed", saved.name)
        assertFalse(saved.verified)
    }

    @Test fun explicitBackupRecoveryWorksWhenIdentityAndContactsAreUnreadable() {
        val backupIdentity = KeyExchange.generateIdentity()
        val backup = KeyExchange.encodeBackup(backupIdentity)
        prefs.edit().putString("identity_encrypted", "KS2:AAAA")
            .putString("contacts_json", "KS2:AAAA").putString("ratchet_old", "unreadable").commit()
        expectFailure { KeyExchange.restoreIdentityFromBackup(context, backup) }
        KeyExchange.restoreIdentityFromBackup(context, backup, discardUnreadableContacts = true)
        assertArrayEquals(backupIdentity.publicKey, KeyExchange.loadIdentity(context)!!.publicKey)
        assertTrue(KeyExchange.listContacts(context).isEmpty())
        assertFalse(prefs.contains("ratchet_old"))
    }

    @Test fun sameIdentityRecoveryCanExplicitlyDiscardUnreadableContacts() {
        val identity = KeyExchange.getOrCreateIdentity(context)
        val backup = KeyExchange.encodeBackup(identity)
        prefs.edit().putString("contacts_json", "KS2:AAAA").putString("ratchet_old", "unreadable").commit()
        KeyExchange.restoreIdentityFromBackup(context, backup, discardUnreadableContacts = true)
        assertArrayEquals(identity.publicKey, KeyExchange.loadIdentity(context)!!.publicKey)
        assertTrue(KeyExchange.listContacts(context).isEmpty())
        assertFalse(prefs.contains("ratchet_old"))
    }

    @Test fun contactsRecoveryRemainsVisibleWhenTheIdentityCannotBeRead() {
        prefs.edit().putString("identity_encrypted", "KS2:AAAA")
            .putString("contacts_json", "KS2:AAAA").commit()
        ActivityScenario.launch<KeyManagementActivity>(Intent(context, KeyManagementActivity::class.java)).use { scenario ->
            val deadline = System.currentTimeMillis() + 5_000
            var found = false
            while (!found && System.currentTimeMillis() < deadline) {
                scenario.onActivity { found = containsText(it.window.decorView, "重建联系人列表") }
                if (!found) Thread.sleep(20)
            }
            assertTrue("contacts recovery must not depend on a readable identity", found)
        }
    }

    private fun containsText(view: View, text: String): Boolean =
        (view is TextView && view.text.toString() == text) ||
            (view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) })

    private fun expectFailure(block: () -> Any?) {
        try { block(); fail("operation must fail closed") } catch (_: IllegalStateException) { }
    }
}
