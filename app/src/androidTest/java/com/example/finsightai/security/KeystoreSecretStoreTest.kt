package com.example.finsightai.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test_secrets_${System.nanoTime()}"
    private val store = KeystoreSecretStore(context, keyAlias = "finsight_test_key_${System.nanoTime()}", prefsName = prefsName)

    @After fun tearDown() { context.deleteSharedPreferences(prefsName) }

    @Test fun roundTripsAndNeverStoresPlaintext() {
        store.put("hf_token", "hf_super_secret_value")
        assertEquals("hf_super_secret_value", store.get("hf_token"))
        val raw = context.getSharedPreferences(prefsName, 0).all.values.joinToString { it.toString() }
        assertFalse("plaintext leaked into SharedPreferences: $raw", raw.contains("hf_super_secret_value"))
    }

    @Test fun removeAndOverwrite() {
        store.put("k", "one"); store.put("k", "two")
        assertEquals("two", store.get("k"))
        store.remove("k")
        assertNull(store.get("k"))
    }
}
