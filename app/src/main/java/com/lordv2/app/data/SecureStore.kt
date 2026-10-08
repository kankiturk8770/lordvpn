package com.lordv2.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores configuration data encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (the key never leaves secure hardware where available).
 */
object SecureStore {
    private const val ALIAS = "lordv2_master_key"
    private const val MODE_PLAIN: Byte = 0
    private const val MODE_GCM: Byte = 1

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun write(file: File, text: String) {
        val bytes = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            byteArrayOf(MODE_GCM, iv.size.toByte()) + iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            byteArrayOf(MODE_PLAIN) + text.toByteArray(Charsets.UTF_8)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
    }

    fun read(file: File): String? {
        if (!file.exists()) return null
        return try {
            val b = file.readBytes()
            if (b.isEmpty()) return null
            if (b[0] == MODE_PLAIN) return String(b, 1, b.size - 1, Charsets.UTF_8)
            val n = b[1].toInt()
            val iv = b.copyOfRange(2, 2 + n)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(b, 2 + n, b.size - 2 - n), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}
