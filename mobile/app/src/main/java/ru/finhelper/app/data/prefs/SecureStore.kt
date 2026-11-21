package ru.finhelper.app.data.prefs

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Шифрование небольших секретов (токенов, ключевой фразы базы данных). */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String?
}

/**
 * Шифрование ключом AES-256-GCM, который создается и хранится в Android Keystore
 * и не покидает защищенное хранилище устройства (п. 4.4.4.1 д ТЗ).
 */
class KeystoreCipher(private val alias: String = "finhelper_master_key") : SecretCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // Вектор инициализации (12 байт) сохраняется перед шифротекстом
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null // ключ утрачен (например, после восстановления из резервной копии) – значение недоступно
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Защищенное хранилище «ключ – значение»: значения записываются в настройки только в зашифрованном виде. */
class SecureStore(private val preferences: SharedPreferences, private val cipher: SecretCipher) {
    fun get(key: String): String? = preferences.getString(key, null)?.let(cipher::decrypt)

    fun put(key: String, value: String) {
        preferences.edit().putString(key, cipher.encrypt(value)).apply()
    }

    fun remove(vararg keys: String) {
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }
}
