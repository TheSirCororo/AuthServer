package ru.cororo.authserver.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

class ComponentSerializer : KSerializer<Component> {
    override val descriptor: SerialDescriptor = SerialDescriptor("Component", JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): Component {
        return GsonComponentSerializer.gson()
            .deserialize(Json.encodeToString(decoder.decodeSerializableValue(JsonElement.serializer())))
    }

    override fun serialize(encoder: Encoder, value: Component) {
        println(Json.encodeToJsonElement(GsonComponentSerializer.gson().serialize(value)))
        encoder.encodeSerializableValue(
            JsonElement.serializer(),
            Json.parseToJsonElement(GsonComponentSerializer.gson().serialize(value))
        )
    }
}