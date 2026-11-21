package ru.finhelper.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import ru.finhelper.app.core.Dates
import java.math.BigDecimal

/**
 * Десятичные числа передаются строками («1234.56»), чтобы исключить ошибки
 * округления. При чтении допускается и строка, и число JSON.
 */
object DecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content else decoder.decodeString()
        return text.toBigDecimal()
    }
}

/** Момент времени: в JSON – строка ISO 8601, в приложении – миллисекунды UTC. */
object InstantMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("InstantMillis", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(Dates.isoInstant(value))

    override fun deserialize(decoder: Decoder): Long = Dates.parseInstant(decoder.decodeString())
}

/** Единые правила разбора JSON: неизвестные поля ответа сервера пропускаются. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
