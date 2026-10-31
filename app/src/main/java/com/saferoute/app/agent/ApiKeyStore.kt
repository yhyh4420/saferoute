package com.saferoute.app.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Meta API key is never compiled into the APK. A developer provisions it once on a test phone
 * (`scripts/provision_key.sh`, debug builds only), and the app immediately re-encrypts it with a
 * non-exportable Android Keystore key and deletes the plaintext. A leaked APK therefore holds no key,
 * and the offline alert path never needs one.
 */
class ApiKeyStore(context: Context) {
    private val dir = context.noBackupFilesDir
    private val imported = File(dir, IMPORT_FILE)
    private val sealed = File(dir, SEALED_FILE)

    @Volatile private var cached: String? = null

    /** The key, or "" when none was provisioned (online features then explain why they are off). */
    fun get(): String {
        cached?.let { return it }
        importIfPresent()
        val key = runCatching { if (sealed.exists()) open(sealed.readBytes()) else "" }.getOrDefault("")
        return key.also { if (it.isNotBlank()) cached = it }
    }

    private fun importIfPresent() {
        if (!imported.exists()) return
        val plain = imported.readText().trim()
        if (plain.isNotBlank()) sealed.writeBytes(seal(plain))
        imported.delete()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    /** IV (12 bytes) followed by AES-GCM ciphertext. */
    private fun seal(plain: String): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return c.iv + c.doFinal(plain.toByteArray())
    }

    private fun open(blob: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return String(c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "saferoute-meta-api"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val IMPORT_FILE = "meta_api_key.import"
        const val SEALED_FILE = "meta_api_key.sealed"
    }
}
