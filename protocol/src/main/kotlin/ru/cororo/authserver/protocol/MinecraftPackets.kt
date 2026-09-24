package ru.cororo.authserver.protocol

import ru.cororo.authserver.protocol.PacketDirection.CLIENTBOUND
import ru.cororo.authserver.protocol.PacketDirection.SERVERBOUND
import ru.cororo.authserver.protocol.ProtocolState.CONFIGURATION
import ru.cororo.authserver.protocol.ProtocolState.HANDSHAKE
import ru.cororo.authserver.protocol.ProtocolState.LOGIN
import ru.cororo.authserver.protocol.ProtocolState.PLAY
import ru.cororo.authserver.protocol.ProtocolState.STATUS
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_11
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_17
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.packet.*
import ru.cororo.authserver.protocol.packet.play.*
import ru.cororo.authserver.protocol.registry.PacketRegistry

/** Every packet the auth server speaks, for all supported versions. */
object MinecraftPackets {
    val registry: PacketRegistry = PacketRegistry.build {
        state(HANDSHAKE) {
            serverbound(HandshakePacket.Codec, "47=0x00")
        }
        state(STATUS) {
            clientbound(StatusResponsePacket.Codec, "47=0x00")
            clientbound(StatusPongPacket.Codec, "47=0x01")
            serverbound(StatusRequestPacket.Codec, "47=0x00")
            serverbound(StatusPingPacket.Codec, "47=0x01")
        }
        state(LOGIN) {
            clientbound(LoginDisconnectPacket.Codec, "47=0x00")
            clientbound(EncryptionRequestPacket.Codec, "47=0x01")
            clientbound(LoginSuccessPacket.Codec, "47=0x02")
            clientbound(SetCompressionPacket.Codec, "47=0x03")
            clientbound(LoginPluginRequestPacket.Codec, "393=0x04")
            serverbound(LoginStartPacket.Codec, "47=0x00")
            serverbound(EncryptionResponsePacket.Codec, "47=0x01")
            serverbound(LoginPluginResponsePacket.Codec, "393=0x02")
            serverbound(LoginAcknowledgedPacket.Codec, "764=0x03")
        }
        state(CONFIGURATION) {
            clientbound("custom_payload", ClientboundPluginMessagePacket.Codec)
            clientbound("disconnect", DisconnectPacket.Codec)
            clientbound("finish_configuration", FinishConfigurationPacket.Codec)
            clientbound("keep_alive", ClientboundKeepAlivePacket.Codec)
            clientbound("ping", PingPacket.Codec)
            clientbound("registry_data", RegistryDataPacket.Codec)
            clientbound("update_enabled_features", FeatureFlagsPacket.Codec)
            clientbound("update_tags", UpdateTagsPacket.Codec)
            clientbound("select_known_packs", ClientboundKnownPacksPacket.Codec)
            clientbound("transfer", TransferPacket.Codec)
            serverbound("client_information", ClientInformationPacket.Codec)
            serverbound("custom_payload", ServerboundPluginMessagePacket.Codec)
            serverbound("finish_configuration", FinishConfigurationAckPacket.Codec)
            serverbound("keep_alive", ServerboundKeepAlivePacket.Codec)
            serverbound("pong", PongPacket.Codec)
            serverbound("select_known_packs", ServerboundKnownPacksPacket.Codec)
        }
        state(PLAY) {
            clientbound("login", JoinGamePacket.Codec)
            clientbound("player_position", PlayerPositionPacket.Codec)
            clientbound("level_chunk", ChunkDataPacket.Codec)
            clientbound("light_update", LightUpdatePacket.Codec)
            clientbound("set_chunk_cache_center", ChunkCacheCenterPacket.Codec)
            clientbound("set_chunk_cache_radius", ChunkCacheRadiusPacket.Codec)
            clientbound("set_default_spawn_position", SpawnPositionPacket.Codec)
            clientbound("set_time", SetTimePacket.Codec)
            clientbound("keep_alive", ClientboundKeepAlivePacket.Codec)
            clientbound("disconnect", DisconnectPacket.Codec)
            clientbound("custom_payload", ClientboundPluginMessagePacket.Codec)
            clientbound("player_abilities", PlayerAbilitiesPacket.Codec)
            clientbound("game_event", GameEventPacket.Codec)
            clientbound("commands", CommandsPacket.Codec)
            clientbound("boss_event", BossBarPacket.Codec)
            clientbound("tab_list", TabListPacket.Codec)
            clientbound("pong_response", PongResponsePacket.Codec)
            clientbound("transfer", TransferPacket.Codec)
            clientbound("update_tags", UpdateTagsPacket.Codec)
            clientbound("container_set_content", ContainerContentPacket.Codec)
            clientbound("container_set_slot", ContainerSlotPacket.Codec)
            clientbound("open_screen", OpenScreenPacket.Codec)
            clientbound("container_close", CloseContainerPacket.Codec)
            clientbound("set_held_slot", SetHeldSlotPacket.Codec)

            val chat = generated(CLIENTBOUND, "chat")
            val legacyTitles = generated(CLIENTBOUND, "set_titles")
            clientbound(SystemChatPacket.Codec, chat.until(MINECRAFT_1_19, generated(CLIENTBOUND, "system_chat")))
            clientbound(TitleTextPacket.Codec, legacyTitles.until(MINECRAFT_1_17, generated(CLIENTBOUND, "set_title_text")), decodes = false)
            clientbound(SubtitleTextPacket.Codec, legacyTitles.until(MINECRAFT_1_17, generated(CLIENTBOUND, "set_subtitle_text")), decodes = false)
            clientbound(TitleTimesPacket.Codec, legacyTitles.until(MINECRAFT_1_17, generated(CLIENTBOUND, "set_titles_animation")), decodes = false)
            clientbound(ClearTitlesPacket.Codec, legacyTitles.until(MINECRAFT_1_17, generated(CLIENTBOUND, "clear_titles")), decodes = false)
            clientbound(
                ActionBarPacket.Codec,
                chat.until(MINECRAFT_1_11, legacyTitles).until(MINECRAFT_1_17, generated(CLIENTBOUND, "set_action_bar_text")),
                decodes = false,
            )

            serverbound("accept_teleportation", TeleportConfirmPacket.Codec)
            serverbound("keep_alive", ServerboundKeepAlivePacket.Codec)
            serverbound("chat", ChatPacket.Codec)
            serverbound("chat_command", ChatCommandPacket.Codec)
            serverbound("chat_command_signed", SignedChatCommandPacket.Codec)
            serverbound("move_player_pos", MovePositionPacket.Codec)
            serverbound("move_player_pos_rot", MovePositionRotationPacket.Codec)
            serverbound("move_player_rot", MoveRotationPacket.Codec)
            serverbound("move_player_status_only", MoveStatusPacket.Codec)
            serverbound("custom_payload", ServerboundPluginMessagePacket.Codec)
            serverbound("client_information", ClientInformationPacket.Codec)
            serverbound("ping_request", PingRequestPacket.Codec)
            serverbound("configuration_acknowledged", ConfigurationAcknowledgedPacket.Codec)
            serverbound("container_click", ClickContainerPacket.Codec)
            serverbound("container_close", ServerboundCloseContainerPacket.Codec)
            serverbound("use_item", UseItemPacket.Codec)
            serverbound("use_item_on", UseItemOnPacket.Codec)
            serverbound("set_carried_item", SetCarriedItemPacket.Codec)
        }
    }
}
