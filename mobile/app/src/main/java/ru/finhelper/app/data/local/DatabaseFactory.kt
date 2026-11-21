package ru.finhelper.app.data.local

import android.content.Context
import androidx.room.Room
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ru.finhelper.app.data.prefs.SecureStore

/** Способ создания локальной базы данных. В тестах подменяется незашифрованной базой в памяти. */
fun interface DatabaseFactory {
    fun create(context: Context): AppDatabase
}

/**
 * Создает базу данных, зашифрованную SQLCipher (AES-256, п. 4.4.4.1 б ТЗ).
 *
 * Ключевая фраза – 32 случайных байта. Она формируется при первом запуске и
 * хранится в [SecureStore] зашифрованной ключом из Android Keystore.
 */
class EncryptedDatabaseFactory(private val secureStore: SecureStore) : DatabaseFactory {
    override fun create(context: Context): AppDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase()))
            .build()
    }

    private fun passphrase(): ByteArray {
        secureStore.get(KEY_PASSPHRASE)?.let { return hexToBytes(it) }
        val bytes = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        secureStore.put(KEY_PASSPHRASE, bytes.joinToString("") { "%02x".format(it) })
        return bytes
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val DATABASE_NAME = "finhelper.db"
        const val KEY_PASSPHRASE = "db_passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
