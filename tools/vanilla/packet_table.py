#!/usr/bin/env python3
"""Build protocol/src/main/resources/.../packet-ids.txt from authoritative sources.

Sources, by priority:
  1. packets.json from Mojang's data generator (1.21+),
  2. PacketIdDump.java over official mappings (1.14.4 - 1.20.4),
  3. archived wiki.vg protocol pages (1.8 - 1.20.6).
Where several sources cover a version they are cross-checked and disagreements abort the build.
Versions documented only as pre-release diffs inherit IDs from a neighbour that is verified identical.
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import CACHE  # noqa: E402
from jars import run_java  # noqa: E402
from versions import RELEASES  # noqa: E402
from wiki_packets import REVISIONS, parse as parse_wiki  # noqa: E402

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / "protocol/src/main/resources/ru/cororo/authserver/protocol/packet-ids.txt"

# Canonical key -> Mojang class names (jar dumps) and wiki.vg section names. Keys follow Mojang's resource names.
PACKETS = {
    ("configuration", "clientbound"): {
        "custom_payload": ["ClientboundCustomPayloadPacket", "Plugin Message"],
        "disconnect": ["ClientboundDisconnectPacket", "Disconnect"],
        "finish_configuration": ["ClientboundFinishConfigurationPacket", "Finish Configuration"],
        "keep_alive": ["ClientboundKeepAlivePacket", "Keep Alive"],
        "ping": ["ClientboundPingPacket", "Ping"],
        "registry_data": ["ClientboundRegistryDataPacket", "Registry Data"],
        "update_enabled_features": ["ClientboundUpdateEnabledFeaturesPacket", "Feature Flags"],
        "update_tags": ["ClientboundUpdateTagsPacket", "Update Tags"],
        "select_known_packs": ["Known Packs"],
        "transfer": ["Transfer"],
    },
    ("configuration", "serverbound"): {
        "client_information": ["ServerboundClientInformationPacket", "Client Information"],
        "custom_payload": ["ServerboundCustomPayloadPacket", "Plugin Message"],
        "finish_configuration": ["ServerboundFinishConfigurationPacket", "Acknowledge Finish Configuration"],
        "keep_alive": ["ServerboundKeepAlivePacket", "Keep Alive"],
        "pong": ["ServerboundPongPacket", "Pong"],
        "resource_pack": ["ServerboundResourcePackPacket", "Resource Pack Response"],
        "select_known_packs": ["Known Packs"],
    },
    ("play", "clientbound"): {
        "login": ["ClientboundLoginPacket", "Join Game", "Login (play)"],
        "player_position": ["ClientboundPlayerPositionPacket", "Player Position And Look", "Synchronize Player Position"],
        "level_chunk": ["ClientboundLevelChunkPacket", "ClientboundLevelChunkWithLightPacket", "Chunk Data",
                        "Chunk Data and Update Light", "Chunk Data And Update Light"],
        "light_update": ["ClientboundLightUpdatePacket", "Update Light"],
        "forget_level_chunk": ["ClientboundForgetLevelChunkPacket", "Unload Chunk"],
        "set_chunk_cache_center": ["ClientboundSetChunkCacheCenterPacket", "Update View Position", "Set Center Chunk"],
        "set_chunk_cache_radius": ["ClientboundSetChunkCacheRadiusPacket", "Update View Distance", "Set Render Distance"],
        "set_default_spawn_position": ["ClientboundSetDefaultSpawnPositionPacket", "ClientboundSetSpawnPositionPacket",
                                       "Spawn Position", "Set Default Spawn Position"],
        "keep_alive": ["ClientboundKeepAlivePacket", "Keep Alive"],
        "disconnect": ["ClientboundDisconnectPacket", "Disconnect", "Disconnect (play)"],
        "custom_payload": ["ClientboundCustomPayloadPacket", "Plugin Message"],
        "chat": ["ClientboundChatPacket", "Chat Message"],
        "system_chat": ["ClientboundSystemChatPacket", "System Chat Message"],
        "set_titles": ["ClientboundSetTitlesPacket", "Title"],
        "set_title_text": ["ClientboundSetTitleTextPacket", "Set Title Text"],
        "set_subtitle_text": ["ClientboundSetSubtitleTextPacket", "Set Subtitle Text"],
        "set_titles_animation": ["ClientboundSetTitlesAnimationPacket", "Set Title Time", "Set Title Animation Times"],
        "clear_titles": ["ClientboundClearTitlesPacket", "Clear Titles"],
        "set_action_bar_text": ["ClientboundSetActionBarTextPacket", "Action Bar", "Set Action Bar Text"],
        "player_abilities": ["ClientboundPlayerAbilitiesPacket", "Player Abilities"],
        "game_event": ["ClientboundGameEventPacket", "Change Game State", "Game Event"],
        "set_time": ["ClientboundSetTimePacket", "Time Update", "Update Time"],
        "commands": ["ClientboundCommandsPacket", "Declare Commands", "Commands"],
        "player_info": ["ClientboundPlayerInfoPacket", "Player List Item", "Player Info"],
        "player_info_update": ["ClientboundPlayerInfoUpdatePacket", "Player Info Update"],
        "boss_event": ["ClientboundBossEventPacket", "Boss Bar"],
        "tab_list": ["ClientboundTabListPacket", "Player List Header And Footer", "Player List Header and Footer",
                     "Tab List Header And Footer", "Set Tab List Header And Footer"],
        "pong_response": ["ClientboundPongResponsePacket", "Pong Response", "Ping Response"],
        "respawn": ["ClientboundRespawnPacket", "Respawn"],
        "block_update": ["ClientboundBlockUpdatePacket", "Block Change", "Block Update"],
        "container_set_slot": ["ClientboundContainerSetSlotPacket", "Set Slot", "Set Container Slot"],
        "container_set_content": ["ClientboundContainerSetContentPacket", "Window Items", "Set Container Content"],
        "transfer": ["Transfer"],
        "change_difficulty": ["ClientboundChangeDifficultyPacket", "Server Difficulty", "Change Difficulty"],
        "update_tags": ["ClientboundUpdateTagsPacket", "Tags", "Update Tags"],
        "open_screen": ["ClientboundOpenScreenPacket", "Open Window", "Open Screen"],
        "container_close": ["ClientboundContainerClosePacket", "Close Window", "Close Container"],
        "set_held_slot": ["ClientboundSetCarriedItemPacket", "Held Item Change", "Set Held Item", "Set Held Slot"],
    },
    ("play", "serverbound"): {
        "accept_teleportation": ["ServerboundAcceptTeleportationPacket", "Teleport Confirm", "Confirm Teleportation"],
        "keep_alive": ["ServerboundKeepAlivePacket", "Keep Alive"],
        "chat": ["ServerboundChatPacket", "Chat Message"],
        "chat_command": ["ServerboundChatCommandPacket", "Chat Command"],
        "chat_command_signed": ["Signed Chat Command"],
        "move_player_pos": ["ServerboundMovePlayerPacket.Pos", "Player Position", "Set Player Position"],
        "move_player_pos_rot": ["ServerboundMovePlayerPacket.PosRot", "Player Position And Look",
                                "Player Position And Rotation", "Set Player Position and Rotation",
                                "Set Player Position And Rotation"],
        "move_player_rot": ["ServerboundMovePlayerPacket.Rot", "Player Look", "Player Rotation", "Set Player Rotation"],
        "move_player_status_only": ["ServerboundMovePlayerPacket", "ServerboundMovePlayerPacket.StatusOnly", "Player",
                                    "Player Movement", "Set Player On Ground", "Set Player Movement Flags"],
        "custom_payload": ["ServerboundCustomPayloadPacket", "Plugin Message"],
        "client_information": ["ServerboundClientInformationPacket", "Client Settings", "Client Information"],
        "ping_request": ["ServerboundPingRequestPacket", "Ping Request"],
        "configuration_acknowledged": ["ServerboundConfigurationAcknowledgedPacket", "Acknowledge Configuration"],
        "command_suggestion": ["ServerboundCommandSuggestionPacket", "Tab-Complete", "Tab Complete",
                               "Command Suggestions Request"],
        "container_click": ["ServerboundContainerClickPacket", "Click Window", "Click Container"],
        "container_close": ["ServerboundContainerClosePacket", "Close Window", "Close Container"],
        "use_item": ["ServerboundUseItemPacket", "Use Item"],
        "use_item_on": ["ServerboundUseItemOnPacket", "Player Block Placement", "Use Item On"],
        "set_carried_item": ["ServerboundSetCarriedItemPacket", "Held Item Change", "Set Held Item"],
    },
}
# Report names differing from our key (Mojang renamed some packets over time).
REPORT_NAMES = {
    "level_chunk": ["minecraft:level_chunk_with_light"],
    "set_held_slot": ["minecraft:set_held_slot", "minecraft:set_carried_item"],
}
# Versions whose documentation is a pre-release diff: (protocol, neighbour it inherits from).
INHERITED = {108: 109, 393: 401, 477: 498, 480: 498, 485: 498, 490: 498}
PROTOCOLS = [protocol for protocol, _, _ in RELEASES]


def normalise(name: str) -> str:
    name = re.sub(r"\s*\((clientbound|serverbound|play|configuration)\)", "", name)
    return re.sub(r"^(clientbound|serverbound)\s+", "", name.strip(), flags=re.IGNORECASE).lower()


def from_packets_json(release: str) -> dict:
    path = CACHE / release / "reports" / "packets.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text())
    result = {}
    for (state, direction), packets in PACKETS.items():
        entries = data.get(state, {}).get(direction, {})
        for key in packets:
            for mojang in REPORT_NAMES.get(key, [f"minecraft:{key}"]):
                if mojang in entries:
                    result[(state, direction, key)] = entries[mojang]["protocol_id"]
                    break
    return result


def from_jar(java: str, release: str) -> dict:
    mappings = CACHE / release / "server_mappings.txt"
    if not mappings.exists() or (CACHE / release / "reports" / "packets.json").exists():
        return {}
    cached = CACHE / release / "packet-ids.txt"
    if not cached.exists():
        cached.write_text(run_java(java, release, HERE / "PacketIdDump.java", mappings))
    names = {}
    for line in cached.read_text().splitlines():
        state, direction, name, packet_id = line.split()
        names[(state.lower().replace("game", "play"), direction, name)] = int(packet_id)
    result = {}
    for (state, direction), packets in PACKETS.items():
        for key, aliases in packets.items():
            for alias in aliases:
                if (state, direction, alias) in names:
                    result[(state, direction, key)] = names[(state, direction, alias)]
    return result


def from_wiki(pages: Path, protocol: int) -> dict:
    path = pages / f"{protocol}.txt"
    if protocol not in REVISIONS or not path.exists():
        return {}
    parsed = parse_wiki(path.read_text(encoding="utf-8"))
    result = {}
    for (state, direction), packets in PACKETS.items():
        section = {normalise(name): packet_id for name, packet_id in parsed.get(state, {}).get(direction, {}).items()}
        for key, aliases in packets.items():
            for alias in aliases:
                if normalise(alias) in section:
                    result[(state, direction, key)] = section[normalise(alias)]
                    break
    return result


def compress(table: dict) -> list:
    lines = []
    for (state, direction), packets in PACKETS.items():
        for key in packets:
            points, previous = [], None
            for protocol in PROTOCOLS:
                packet_id = table[protocol].get((state, direction, key))
                if packet_id != previous:
                    points.append(f"{protocol}=" + ("-" if packet_id is None else f"0x{packet_id:02x}"))
                    previous = packet_id
            if any(not point.endswith("=-") for point in points):
                lines.append(f"{state} {direction} {key} {' '.join(points)}")
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wiki", type=Path, required=True, help="Directory with cached wiki page revisions")
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    table, problems = {}, []
    for protocol, release, _ in RELEASES:
        sources = {"packets.json": from_packets_json(release), "jar": from_jar(args.java, release),
                   "wiki": from_wiki(args.wiki, protocol)}
        merged = {}
        for name, source in sources.items():
            for key, packet_id in source.items():
                if key in merged and merged[key][1] != packet_id:
                    problems.append(f"{protocol} {key}: {merged[key][0]}={merged[key][1]:#x} vs {name}={packet_id:#x}")
                merged.setdefault(key, (name, packet_id))
        table[protocol] = {key: packet_id for key, (_, packet_id) in merged.items()}
    for protocol, neighbour in INHERITED.items():
        if table[protocol]:
            problems.append(f"{protocol} unexpectedly has its own data; remove it from INHERITED")
        table[protocol] = dict(table[neighbour])
    if problems:
        print("\n".join(problems), file=sys.stderr)
        sys.exit(1)
    header = ["# Generated by tools/vanilla/packet_table.py - do not edit.",
              "# <state> <direction> <packet> <first protocol>=<id|-> ..."]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(header + compress(table)) + "\n")
    print(f"Wrote {OUTPUT}")


if __name__ == "__main__":
    main()
