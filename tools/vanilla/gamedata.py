#!/usr/bin/env python3
"""Generate gamedata module resources from cached vanilla data.

Inputs: data generator reports (reports.py), probe recordings (probe.py) and Mojang's DataFixer (Upgrade.java).
Output: gamedata/src/main/resources/ru/cororo/authserver/gamedata/
  blocks.txt.gz            canonical (newest release) block states in network ID order, with a solidity flag
  items.txt.gz             canonical item names in network ID order
  <protocol>/version.properties, registries.bin.gz, tags.bin.gz, codec.nbt.gz, dimension.nbt.gz
  <protocol>/blocks.bin.gz canonical block state -> network state of that version (int32 array)
  <protocol>/items.bin.gz  canonical item -> network item of that version (int32; id << 16 | damage before 1.13)

Mapping: every state of an old release is upgraded with DataFixer - the path Mojang uses to open old worlds - and
the result is inverted. Canonical states without an equivalent fall back to the same block with the closest
properties, then to a similar block family, then to stone or air by solidity.
"""
import argparse
import gzip
import json
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import CACHE  # noqa: E402
from jars import run_java  # noqa: E402
from versions import DATA_VERSION, RELEASES  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUTPUT = ROOT / "gamedata/src/main/resources/ru/cororo/authserver/gamedata"
PROBE = ROOT / "tools/probe/build/install/probe/bin/probe"
NEWEST = RELEASES[-1]
FLATTENING = 393

# Highest legacy block / item IDs of each pre-flattening release (structure_block, 255, exists since 1.9).
LEGACY_MAX_BLOCK = {47: 197, 107: 212, 108: 212, 109: 212, 110: 212, 210: 217, 315: 234, 316: 234, 335: 252,
                    338: 252, 340: 252}
LEGACY_MAX_ITEM = {47: 431, 107: 448, 108: 448, 109: 448, 110: 448, 210: 448, 315: 450, 316: 452, 335: 453,
                   338: 453, 340: 453}
LEGACY_RECORDS = range(2256, 2268)
# Items introduced after 1.8 never had numeric IDs in world files, so DataFixer's ItemIdFix does not know them.
LATE_LEGACY_ITEMS = {
    432: "chorus_fruit", 433: "popped_chorus_fruit", 434: "beetroot", 435: "beetroot_seeds", 436: "beetroot_soup",
    437: "dragon_breath", 438: "splash_potion", 439: "spectral_arrow", 440: "tipped_arrow", 441: "lingering_potion",
    442: "shield", 443: "elytra", 444: "spruce_boat", 445: "birch_boat", 446: "jungle_boat", 447: "acacia_boat",
    448: "dark_oak_boat", 449: "totem_of_undying", 450: "shulker_shell", 452: "iron_nugget", 453: "knowledge_book",
}

# Block families: a missing block ending with the suffix is replaced by the first candidate the version has.
FAMILIES = [
    ("_wall_hanging_sign", ["oak_wall_sign"]), ("_hanging_sign", ["oak_sign"]), ("_wall_sign", ["oak_wall_sign"]),
    ("_sign", ["oak_sign"]), ("_stairs", ["oak_stairs", "cobblestone_stairs"]),
    ("_slab", ["oak_slab", "smooth_stone_slab", "stone_slab"]), ("_wall", ["cobblestone_wall"]),
    ("_fence_gate", ["oak_fence_gate"]), ("_fence", ["oak_fence"]), ("_trapdoor", ["oak_trapdoor"]),
    ("_door", ["oak_door"]), ("_button", ["oak_button", "stone_button"]),
    ("_pressure_plate", ["oak_pressure_plate", "stone_pressure_plate"]), ("_leaves", ["oak_leaves"]),
    ("_log", ["oak_log"]), ("_stem", ["oak_log"]), ("_wood", ["oak_wood", "oak_log"]), ("_hyphae", ["oak_wood", "oak_log"]),
    ("_planks", ["oak_planks"]), ("_sapling", ["oak_sapling"]), ("_propagule", ["oak_sapling"]),
    ("_carpet", ["white_carpet"]), ("_wool", ["white_wool"]),
    ("_stained_glass_pane", ["white_stained_glass_pane", "glass_pane"]),
    ("_stained_glass", ["white_stained_glass", "glass"]), ("_glazed_terracotta", ["white_glazed_terracotta", "terracotta"]),
    ("_terracotta", ["terracotta"]), ("_concrete_powder", ["white_concrete_powder", "sand"]),
    ("_concrete", ["white_concrete", "white_wool"]), ("_shulker_box", ["shulker_box", "chest"]),
    ("_bed", ["red_bed"]), ("_wall_banner", ["white_wall_banner"]), ("_banner", ["white_banner"]),
    ("_candle_cake", ["cake"]), ("_candle", ["torch"]), ("_ore", ["stone"]), ("_bricks", ["stone_bricks", "bricks"]),
    ("_tiles", ["stone_bricks"]), ("_coral_block", ["red_wool"]), ("_coral_fan", ["air"]), ("_coral", ["air"]),
    ("_mushroom", ["red_mushroom"]), ("_flower", ["poppy"]), ("_tulip", ["poppy"]), ("_lantern", ["torch"]),
    ("_torch", ["torch"]), ("_wall_torch", ["wall_torch"]), ("_rail", ["rail"]), ("_chain", ["chain", "iron_bars"]),
    ("_bars", ["iron_bars"]), ("_head", ["skeleton_skull"]), ("_skull", ["skeleton_skull"]),
    ("_vines", ["vine"]), ("_roots", ["dead_bush"]), ("_nylium", ["netherrack"]), ("_grate", ["glass"]),
    ("_bulb", ["redstone_lamp"]), ("_block", ["stone"]),
]
COLOURS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
           "purple", "blue", "brown", "green", "red", "black"]
# Coloured blocks keep their colour: red_concrete becomes red_wool on versions without concrete.
COLOURED = {"concrete": ["concrete", "wool"], "concrete_powder": ["concrete_powder", "wool"],
            "glazed_terracotta": ["glazed_terracotta", "terracotta"], "shulker_box": ["shulker_box", "wool"],
            "candle": ["candle"], "candle_cake": [], "bed": ["bed"], "banner": ["banner"], "wall_banner": ["wall_banner"],
            "carpet": ["carpet"], "stained_glass": ["stained_glass"], "stained_glass_pane": ["stained_glass_pane"],
            "terracotta": ["terracotta"], "wool": ["wool"], "dye": ["dye"]}
# Whole-name replacements for notable blocks without a family.
EXACT = {
    "copper_block": ["orange_terracotta"], "cut_copper": ["orange_terracotta"], "chiseled_copper": ["orange_terracotta"],
    "copper_grate": ["glass"], "copper_bulb": ["redstone_lamp"], "raw_copper_block": ["orange_terracotta"],
    "raw_iron_block": ["iron_block"], "raw_gold_block": ["gold_block"], "amethyst_block": ["purple_wool"],
    "tinted_glass": ["black_stained_glass", "glass"], "moss_block": ["green_wool"], "moss_carpet": ["green_carpet"],
    "sculk": ["black_wool"], "honey_block": ["slime_block"], "honeycomb_block": ["orange_terracotta"],
    "target": ["hay_block"], "lodestone": ["stone_bricks"], "shroomlight": ["glowstone"], "soul_lantern": ["torch"],
    "soul_fire": ["fire"], "soul_soil": ["soul_sand"], "ancient_debris": ["netherrack"], "respawn_anchor": ["obsidian"],
    "light": ["air"], "barrier": ["air"], "structure_void": ["air"], "short_grass": ["grass", "tall_grass"],
    "chain": ["iron_bars"], "scaffolding": ["air"], "bell": ["gold_block"], "lantern": ["torch"],
    "smooth_stone": ["stone_slab", "stone"], "blue_ice": ["packed_ice", "ice"], "kelp": ["air"], "seagrass": ["air"],
}
# Missing materials, checked as infixes after exact names and colours.
MATERIALS = [
    ("cobbled_deepslate", "cobblestone"),
    ("polished_deepslate", "stone_bricks"), ("deepslate", "stone"), ("blackstone", "cobblestone"),
    ("basalt", "stone"), ("tuff", "andesite"), ("calcite", "diorite"), ("packed_mud", "dirt"), ("mud_brick", "brick"),
    ("mud", "dirt"), ("cut_copper", "stone"), ("copper", "stone"), ("amethyst", "purple_stained_glass"), ("sculk", "black_wool"),
    ("moss", "green_wool"), ("dripstone", "stone"), ("prismarine", "stone"), ("quartz", "quartz_block"),
    ("crimson", "oak"), ("warped", "oak"), ("mangrove", "oak"), ("cherry", "oak"), ("bamboo", "oak"),
    ("pale_oak", "oak"), ("crying_obsidian", "obsidian"), ("netherite", "iron_block"),
]


def gz_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.GzipFile(path, "wb", mtime=0) as output:
        output.write(data)


def parse_state(state: str):
    if "[" not in state:
        return state, {}
    name, rest = state[:-1].split("[", 1)
    return name, dict(pair.split("=", 1) for pair in rest.split(","))


def format_state(name: str, properties: dict) -> str:
    if not properties:
        return name
    return name + "[" + ",".join(f"{key}={properties[key]}" for key in sorted(properties)) + "]"


# ------------------------------------------------------------------------------------------------ data collection

def modern_block_states(release: str):
    """(network id, state) for every block state of a 1.13+ release."""
    report = json.loads((CACHE / release / "reports" / "blocks.json").read_text())
    for name, block in report.items():
        for state in block["states"]:
            yield state["id"], format_state(name, state.get("properties", {}))


def modern_items(release: str):
    reports = CACHE / release / "reports"
    if (reports / "registries.json").exists():
        entries = json.loads((reports / "registries.json").read_text())["minecraft:item"]["entries"]
    else:
        entries = json.loads((reports / "items.json").read_text())
    return {name: entry["protocol_id"] for name, entry in entries.items()}


def upgrade(java: str, mode: str, lines: list, work: Path) -> list:
    source, target = work / f"{mode}-in.tsv", work / f"{mode}-out.tsv"
    source.write_text("\n".join(lines) + "\n")
    run_java(java, NEWEST[1], HERE / "Upgrade.java", mode, source, target, cwd=work)
    return target.read_text().splitlines()


def flatten(java: str, mode: str, work: Path) -> list:
    """Pre-1.13 IDs flattened and upgraded to the newest release, as tab-separated columns."""
    target = work / f"{mode}.tsv"
    run_java(java, NEWEST[1], HERE / "Upgrade.java", mode, target, cwd=work)
    return [line.split("\t") for line in target.read_text().splitlines() if line]


# ---------------------------------------------------------------------------------------------------- inversion

class Canonical:
    def __init__(self, lines: list):
        self.states, self.solid, self.default = [], [], []
        for line in lines:
            _, state, solid, default = line.split("\t")
            self.states.append(state)
            self.solid.append(solid == "1")
            self.default.append(default == "1")
        self.index = {state: number for number, state in enumerate(self.states)}


def invert(pairs, canonical: Canonical, fallback_name: str = "minecraft:stone", air: str = "minecraft:air"):
    """pairs: (version id, canonical state string) ordered by preference. Returns canonical id -> version id."""
    pairs = list(pairs)
    canonical_by_name = {}
    for number, state in enumerate(canonical.states):
        name, properties = parse_state(state)
        canonical_by_name.setdefault(name, []).append((properties, number))
    direct, by_name = {}, {}
    for version_id, state in pairs:
        name, properties = parse_state(state)
        by_name.setdefault(name, []).append((properties, version_id))
        if state in canonical.index:
            direct.setdefault(canonical.index[state], version_id)
    # Upgraded legacy states may lack properties that did not exist yet (e.g. waterlogged): they stand for every
    # canonical state that agrees on the properties they do have.
    for version_id, state in pairs:
        if state not in canonical.index:
            name, properties = parse_state(state)
            for canonical_properties, number in canonical_by_name.get(name, []):
                if all(canonical_properties.get(key) == value for key, value in properties.items()):
                    direct.setdefault(number, version_id)

    def closest(name: str, properties: dict):
        candidates = by_name.get(name)
        if not candidates:
            return None
        return max(candidates, key=lambda c: sum(c[0].get(k) == v for k, v in properties.items()))[1]

    def substitute(name: str, properties: dict):
        for candidate in substitutes(name.removeprefix("minecraft:")):
            found = closest("minecraft:" + candidate, properties)
            if found is not None:
                return found
        return None

    stone, air_id = closest(fallback_name, {}), closest(air, {})
    mapping = []
    for number, state in enumerate(canonical.states):
        if number in direct:
            mapping.append(direct[number])
            continue
        name, properties = parse_state(state)
        found = closest(name, properties)
        if found is None:
            found = substitute(name, properties)
        if found is None:
            found = stone if canonical.solid[number] else air_id
        mapping.append(found)
    return mapping


def substitutes(short: str) -> list:
    """Replacement names for a block or item a version lacks, best first."""
    result = []
    for prefix in ("waxed_", "exposed_", "weathered_", "oxidized_"):
        short = short.removeprefix(prefix)
    if short in EXACT:
        result += EXACT[short]
    colour = next((c for c in COLOURS if short.startswith(c + "_")), None)
    if colour:
        rest = short[len(colour) + 1:]
        result += [f"{colour}_{replacement}" for replacement in COLOURED.get(rest, [])]
    names = [short]
    for old, new in MATERIALS:
        if old in short:
            names.append(short.replace(old, new).strip("_"))
    result += names[1:]
    for candidate in names:
        for suffix, replacements in FAMILIES:
            if candidate.endswith(suffix):
                result += replacements
                break
    return result


def write_upgrade_tables(canonical: Canonical, legacy_blocks: list, requests: list, upgraded: list):
    """Tables that let map loaders read old data: legacy numeric blocks and renamed/reshaped states."""
    legacy = [-1] * 4096
    for legacy_id, state in legacy_blocks:
        legacy[int(legacy_id)] = canonical.index.get(state, -1)
    gz_write(OUTPUT / "legacy-blocks.bin.gz", pack(legacy))
    renames = {}
    for (_, _, line), state in zip(requests, upgraded):
        old = line.split("\t", 1)[1]
        if old != state and state in canonical.index and old not in canonical.index:
            renames.setdefault(old, canonical.index[state])
    gz_write(OUTPUT / "renames.txt.gz", "\n".join(f"{old}\t{new}" for old, new in sorted(renames.items())).encode())
    print(f"Upgrade tables: {sum(1 for v in legacy if v >= 0)} legacy states, {len(renames)} renamed states")


def pack(values: list) -> bytes:
    return struct.pack(f">{len(values)}i", *values)


# -------------------------------------------------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, help="JDK 25 java executable")
    args = parser.parse_args()
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="authserver-gamedata-") as temporary:
        work = Path(temporary)
        run_java(args.java, NEWEST[1], HERE / "Upgrade.java", "canonical", work / "canonical.tsv", cwd=work)
        canonical = Canonical((work / "canonical.tsv").read_text().splitlines())
        gz_write(OUTPUT / "blocks.txt.gz", "\n".join(f"{state}\t{int(solid)}\t{int(default)}" for state, solid, default
                                                     in zip(canonical.states, canonical.solid, canonical.default)).encode())
        canonical_items = sorted(modern_items(NEWEST[1]).items(), key=lambda item: item[1])
        gz_write(OUTPUT / "items.txt.gz", "\n".join(name for name, _ in canonical_items).encode())
        item_index = {name: number for number, (name, _) in enumerate(canonical_items)}

        # Upgrade every modern state and item name in one DataFixer run.
        block_requests, item_requests = [], []
        for protocol, release, _ in RELEASES:
            if protocol >= FLATTENING:
                block_requests += [(protocol, vid, f"{DATA_VERSION[protocol]}\t{state}") for vid, state in modern_block_states(release)]
                item_requests += [(protocol, vid, f"{DATA_VERSION[protocol]}\t{name}") for name, vid in modern_items(release).items()]
        upgraded_blocks = upgrade(args.java, "blocks", [line for _, _, line in block_requests], work)
        upgraded_items = upgrade(args.java, "items", [line for _, _, line in item_requests], work)
        legacy_blocks = flatten(args.java, "legacy-blocks", work)
        legacy_items = legacy_item_candidates(flatten(args.java, "legacy-items", work), legacy_blocks, item_index)

        write_upgrade_tables(canonical, legacy_blocks, block_requests, upgraded_blocks)
        previous = {}
        for protocol, release, _ in RELEASES:
            if protocol >= FLATTENING:
                blocks = sorted((vid, state) for (p, vid, _), state in zip(block_requests, upgraded_blocks) if p == protocol)
                items = sorted((vid, name) for (p, vid, _), name in zip(item_requests, upgraded_items) if p == protocol)
            else:
                blocks = [(int(legacy), state) for legacy, state in legacy_blocks
                          if legacy_block_exists(int(legacy) >> 4, protocol)]
                # Candidate order matters: real items from DataFixer beat items derived from blocks.
                items = [((int(i) << 16) | int(damage), name) for i, damage, name in legacy_items
                         if legacy_item_exists(int(i), protocol)]
            block_map = invert(blocks, canonical)
            item_map = invert_items(items, item_index, canonical_items)
            directory = OUTPUT / str(protocol)
            write_mapping(directory, "blocks", block_map, previous)
            write_mapping(directory, "items", item_map, previous)
            print(f"{release}: mapped {len(blocks)} block states and {len(items)} items", flush=True)
    extract_registries(args.java)


def legacy_block_exists(block: int, protocol: int) -> bool:
    return block <= LEGACY_MAX_BLOCK[protocol] or (block == 255 and protocol >= 107)


def legacy_item_exists(item: int, protocol: int) -> bool:
    if item < 256:
        return legacy_block_exists(item, protocol)
    return item <= LEGACY_MAX_ITEM[protocol] or item in LEGACY_RECORDS


def legacy_item_candidates(from_fixer: list, legacy_blocks: list, item_index: dict) -> list:
    """(id, damage, item) rows: DataFixer's numeric items, block items and post-1.8 items."""
    rows = [tuple(row) for row in from_fixer]
    known = {(int(i), int(d)) for i, d, _ in rows}
    for legacy, state in legacy_blocks:
        block, meta = int(legacy) >> 4, int(legacy) & 15
        name = parse_state(state)[0]
        if block and (block, meta) not in known and name in item_index:
            rows.append((str(block), str(meta), name))
            known.add((block, meta))
    rows += [(str(i), "0", "minecraft:" + name) for i, name in LATE_LEGACY_ITEMS.items()]
    return rows


def invert_items(items, item_index: dict, canonical_items: list) -> list:
    direct = {}
    for version_id, name in items:
        if name in item_index:
            direct.setdefault(item_index[name], version_id)
    names = {name: version_id for version_id, name in reversed(items)}
    stone = names.get("minecraft:stone", 1 << 16)
    mapping = []
    for number, (name, _) in enumerate(canonical_items):
        if number in direct:
            mapping.append(direct[number])
            continue
        replacement = next((names["minecraft:" + candidate] for candidate in substitutes(name.removeprefix("minecraft:"))
                            if "minecraft:" + candidate in names), None)
        mapping.append(replacement if replacement is not None else stone)
    return mapping


def write_mapping(directory: Path, kind: str, values: list, previous: dict):
    """Identical mappings are stored once; later versions reference the first protocol that has them."""
    data = pack(values)
    properties = directory / f"{kind}.ref"
    if data in previous:
        directory.mkdir(parents=True, exist_ok=True)
        properties.write_text(previous[data])
    else:
        gz_write(directory / f"{kind}.bin.gz", data)
        previous[data] = directory.name


def extract_registries(java: str):
    env_home = str(Path(java).resolve().parents[1])
    for protocol, release, _ in RELEASES:
        recording = CACHE / release / "recording"
        if not recording.exists():
            raise SystemExit(f"Missing recording for {release}; run probe.py first")
        directory = OUTPUT / str(protocol)
        subprocess.run([str(PROBE), "extract", str(protocol), str(recording), str(directory)], check=True,
                       env={"JAVA_HOME": env_home, "PATH": "/usr/bin:/bin"}, stdout=subprocess.DEVNULL)
        lines = []
        blocks_report = CACHE / release / "reports" / "blocks.json"
        if blocks_report.exists():
            lines.append(f"blockStateCount={sum(len(b['states']) for b in json.loads(blocks_report.read_text()).values())}")
        registries = CACHE / release / "reports" / "registries.json"
        if registries.exists():
            types = json.loads(registries.read_text()).get("minecraft:command_argument_type", {}).get("entries", {})
            if "brigadier:string" in types:
                lines.append(f"stringArgumentParser={types['brigadier:string']['protocol_id']}")
        with open(directory / "version.properties", "a") as output:
            output.write("".join(line + "\n" for line in lines))


if __name__ == "__main__":
    main()
