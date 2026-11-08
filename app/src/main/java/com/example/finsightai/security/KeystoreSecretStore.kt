package com.example.finsightai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when a secret cannot be stored safely. Values are never included in the message. */
class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [SecretStore] that encrypts every value with an AES-256-GCM key held in the Android Keystore
 * (hardware-backed where available) and keeps only `iv:ciphertext` (base64) in a private
 * SharedPreferences file. The key never leaves the Keystore. If the Keystore is unavailable,
 * [put] throws [SecretStoreException] instead of falling back to plaintext; [get] returns null for
 * entries that can no longer be decrypted (e.g. after a restore onto a new device) and drops them.
 */
class KeystoreSecretStore(
    context: Context,
    private val keyAlias: String = DEFAULT_ALIAS,
    prefsName: String = "finsight_secrets",
) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            decrypt(key, stored)
        } catch (_: GeneralSecurityException) {
            prefs.edit().remove(key).commit()
            null
        } catch (_: ProviderException) {
            prefs.edit().remove(key).commit()
            null
        } catch (_: IllegalArgumentException) {
            prefs.edit().remove(key).commit()
            null
        }
    }

    override fun put(key: String, value: String) {
        val encoded = try {
            encrypt(key, value)
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException("Secure storage is unavailable on this device.", e)
        } catch (e: ProviderException) {
            throw SecretStoreException("Secure storage is unavailable on this device.", e)
        }
        // commit(): callers rely on the value being durable before they use it (token rotation).
        if (!prefs.edit().putString(key, encoded).commit()) {
            throw SecretStoreException("Could not write to secure storage.")
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    private fun encrypt(name: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, stored: String): String {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "malformed secret" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8)) // binds the ciphertext to its key name
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "finsight_secret_store_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
