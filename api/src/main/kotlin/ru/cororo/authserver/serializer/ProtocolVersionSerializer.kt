package ru.cororo.authserver.serializer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersions

@OptIn(ExperimentalSerializationApi::class)
class ProtocolVersionSerializer : KSerializer<ProtocolVersion> {
    override val descriptor: SerialDescriptor = SerialDescriptor("ProtocolVersion", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ProtocolVersion {
        return ProtocolVersions.v1_18_2 // not needed on server side
    }

    override fun serialize(encoder: Encoder, value: ProtocolVersion) {
        encoder.encodeSerializableValue(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "name",
                    ProtocolVersions.defaults.first().possibleVersions.first() + "-" + ProtocolVersions.defaults.last().possibleVersions.last()
                )
                put("protocol", value.raw)
            }
        )
    }
}