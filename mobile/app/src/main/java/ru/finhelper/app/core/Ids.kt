package ru.finhelper.app.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Формирование идентификаторов записей. */
object Ids {
    /** Новый случайный идентификатор (UUID версии 4). Создается на устройстве, поэтому запись можно добавить без сети. */
    fun random(): String = UUID.randomUUID().toString()

    /**
     * Идентификатор UUID версии 5 (SHA-1) по пространству имен и имени.
     * Совпадает с результатом uuid.uuid5 серверной части, благодаря чему системные
     * категории имеют одинаковые идентификаторы на устройстве и на сервере.
     */
    fun uuid5(namespace: UUID, name: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(
            ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        )
        val hash = digest.digest(name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long).toString()
    }
}
