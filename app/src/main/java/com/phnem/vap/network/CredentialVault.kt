package com.phnem.vap.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound credentials. No plaintext fallback or passwords in saved-instance state. */
class CredentialVault(context: Context, private val namespace: String = "provider_credentials") {
    init { require(namespace.matches(Regex("[a-z0-9_-]{1,80}"))) }
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val applicationId = context.packageName
    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    suspend fun get(name: String): String? = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-z0-9_.-]{1,80}")))
        val encoded = prefs.getString(name, null) ?: return@withContext null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 30 && bytes[0] == 1.toByte())
        val ivLength = bytes[1].toInt() and 255
        require(ivLength in 12..32 && bytes.size > 2 + ivLength + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 2, ivLength))
        cipher.updateAAD("$applicationId:$namespace:$name".toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength), Charsets.UTF_8)
    }
    suspend fun save(values: Map<String, String?>) = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        values.forEach { (name, value) ->
            require(name.matches(Regex("[a-z0-9_.-]{1,80}")))
            if (value == null) edit.remove(name) else {
                require(value.toByteArray().size in 1..8192)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD("$applicationId:$namespace:$name".toByteArray(Charsets.UTF_8))
                val bytes = byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                edit.putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        check(edit.commit()) { "Cannot save credentials" }
    }
    private companion object { const val ALIAS = "vap_provider_credentials_v1"; val lock = Any() }
}
