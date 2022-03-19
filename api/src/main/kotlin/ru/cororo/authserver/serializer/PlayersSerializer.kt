package ru.cororo.authserver.serializer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import ru.cororo.authserver.ServerInfo
import ru.cororo.authserver.protocol.ProtocolVersions

@OptIn(ExperimentalSerializationApi::class)
class PlayersSerializer : KSerializer<ServerInfo.Players> {
    override val descriptor: SerialDescriptor = SerialDescriptor("Players", JsonObject.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ServerInfo.Players {
        return ServerInfo.Players(0, 0, arrayOf()) // not needed for server side
    }

    override fun serialize(encoder: Encoder, value: ServerInfo.Players) {
        encoder.encodeSerializableValue(
            JsonObject.serializer(),
            buildJsonObject {
                put("max", value.max)
                put("online", value.online)
                put("sample", JsonArray(value.sample.toList().map { JsonPrimitive(it) }))
            }
        )
    }
}